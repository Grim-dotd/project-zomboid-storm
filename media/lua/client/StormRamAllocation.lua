-- Low-memory popup. A vanilla client gets the Storm Launcher setup steps, because the launcher
-- sizes the game's memory. A Storm client already runs the launcher, which chose the current size
-- on purpose, so it only sees the popup when the server sends showRamAlloc, and the steps point at
-- the launcher's Game memory setting.
require("ISUI/ISPanelJoypad")
require("ISUI/ISButton")
require("ISUI/ISLabel")
require("ISUI/ISRichTextPanel")
require("ISUI/ISTextEntryBox")
require("ISUI/ISTickBox")
require("PersistedTable")

StormRamAllocation = {}

local MODULE = "StormDiagnostics"
local COMMAND = "ramAlloc"
local COMMAND_SHOW_RAM_ALLOC = "showRamAlloc"
local SETTINGS_FILE = "StormRamAllocationSettings.txt"
local LOW_RAM_THRESHOLD_GIB = 4
local MB_PER_GIB = 1073

local WORKSHOP_ID = "3670772371"
local LAUNCH_ARGS = {
    {
        labelKey = "UI_Storm_LowRam_Windows",
        args = "-agentpath:../../workshop/content/108600/"
            .. WORKSHOP_ID
            .. "/mods/storm/bootstrap/agentlib.dll=storm-bootstrap.jar --",
    },
    {
        labelKey = "UI_Storm_LowRam_Linux",
        args = "-javaagent:../../../workshop/content/108600/"
            .. WORKSHOP_ID
            .. "/mods/storm/bootstrap/storm-bootstrap.jar --",
    },
    {
        labelKey = "UI_Storm_LowRam_Mac",
        args = "-javaagent:../../../../../workshop/content/108600/"
            .. WORKSHOP_ID
            .. "/mods/storm/bootstrap/storm-bootstrap.jar --",
    },
}
local BENEFIT_KEYS = {
    "UI_Storm_LowRam_Benefit1",
    "UI_Storm_LowRam_Benefit2",
    "UI_Storm_LowRam_Benefit3",
    "UI_Storm_LowRam_Benefit4",
    "UI_Storm_LowRam_Benefit5",
}
local IMAGE_STEAM = "media/ui/storm/steamproperties.png"
local IMAGE_LAUNCHER = "media/ui/storm/launcher.png"

local FONT = UIFont.Small
local FONT_HGT = getTextManager():getFontHeight(FONT)
local PAD = 12
local ROW_HGT = FONT_HGT + 10
local ROW_GAP = 6
local LABEL_WID = 70
local COPY_WID = 80
local MAX_WIDTH = 900
local MAX_IMAGE_WIDTH = 640
local COPIED_MS = 1500
local JOYPAD_SCROLL_STEP = 60

local function hasStorm()
    return type(Storm) == "table"
end

local function loadDontShowAgain()
    local settings = PersistedTable:read(SETTINGS_FILE)
    return settings.dontShowAgain == "true"
end

local function saveDontShowAgain()
    local settings = PersistedTable:read(SETTINGS_FILE)
    settings.dontShowAgain = true
    PersistedTable:save(SETTINGS_FILE, settings)
end

local function imageTag(path, maxWidth)
    local texture = getTexture(path)
    if not texture then
        return ""
    end
    local w = texture:getWidth()
    local h = texture:getHeight()
    if w <= 0 or h <= 0 then
        return ""
    end
    local scale = math.min(1, maxWidth / w)
    return string.format(
        " <LINE> <IMAGECENTRE:%s,%d,%d> <LINE> ",
        path,
        math.floor(w * scale),
        math.floor(h * scale)
    )
end

local function step(n, key)
    return " <LINE> <RGB:1,0.75,0.35> "
        .. n
        .. ". <RGB:1,1,1> <SPACE> "
        .. getText(key)
        .. " <LINE> "
end

local function heading(key)
    return " <LINE> <RGB:1,0.55,0.25> " .. getText(key) .. " <RGB:1,1,1> <LINE> "
end

local function benefits()
    local text = heading("UI_Storm_LowRam_BenefitsTitle")
    for _, key in ipairs(BENEFIT_KEYS) do
        text = text .. " - " .. getText(key) .. " <LINE> "
    end
    return text
end

local function title(introKey, maxMb)
    return " <SIZE:large> "
        .. getText("UI_Storm_LowRam_Title")
        .. " <LINE> <SIZE:medium> "
        .. " <LINE> "
        .. getText(introKey, string.format("%.1f", maxMb / MB_PER_GIB))
        .. " <LINE> "
end

local function launcherSetupText(maxMb, imageWidth)
    return title("UI_Storm_LowRam_Intro", maxMb)
        .. benefits()
        .. heading("UI_Storm_LowRam_StepsTitle")
        .. step(1, "UI_Storm_LowRam_Step1")
        .. imageTag(IMAGE_STEAM, imageWidth)
        .. step(2, "UI_Storm_LowRam_Step2")
        .. imageTag(IMAGE_LAUNCHER, imageWidth)
end

local function launcherSettingsText(maxMb)
    return title("UI_Storm_LowRam_ManagedIntro", maxMb)
        .. heading("UI_Storm_LowRam_ManagedStepsTitle")
        .. step(1, "UI_Storm_LowRam_ManagedStep1")
        .. step(2, "UI_Storm_LowRam_ManagedStep2")
        .. step(3, "UI_Storm_LowRam_ManagedStep3")
        .. " <LINE> "
        .. getText("UI_Storm_LowRam_ManagedNote")
        .. " <LINE> "
end

local Panel = ISPanelJoypad:derive("StormRamAllocationPanel")

function Panel:new(maxMb, launcherManaged)
    local screenW = getCore():getScreenWidth()
    local screenH = getCore():getScreenHeight()
    local w = math.min(MAX_WIDTH, screenW - 40)
    local h = screenH - 40
    local o = ISPanelJoypad:new((screenW - w) / 2, (screenH - h) / 2, w, h)
    setmetatable(o, self)
    self.__index = self
    o.backgroundColor = { r = 0.04, g = 0.04, b = 0.05, a = 0.96 }
    o.borderColor = { r = 0.9, g = 0.55, b = 0.2, a = 1 }
    o.moveWithMouse = false
    o.maxMb = maxMb
    o.launcherManaged = launcherManaged
    o.copyButtons = {}
    return o
end

function Panel:createChildren()
    ISPanelJoypad.createChildren(self)
    local argsRows = self.launcherManaged and 0 or #LAUNCH_ARGS
    local footerHgt = (argsRows + 1) * (ROW_HGT + ROW_GAP) + PAD
    local maxTextHgt = self.height - PAD - footerHgt

    local text
    if self.launcherManaged then
        text = launcherSettingsText(self.maxMb)
    else
        local imageWidth = math.min(MAX_IMAGE_WIDTH, self.width - PAD * 2 - 60)
        text = launcherSetupText(self.maxMb, imageWidth)
    end
    local textHgt = self:addRichText(text, maxTextHgt)

    self:setHeight(textHgt + PAD + footerHgt)
    self:setY((getCore():getScreenHeight() - self.height) / 2)

    local y = textHgt + PAD
    if not self.launcherManaged then
        for _, row in ipairs(LAUNCH_ARGS) do
            self:addArgsRow(y, row.labelKey, row.args)
            y = y + ROW_HGT + ROW_GAP
        end
    end

    local tickHgt = FONT_HGT + 4
    self.tickBox =
        ISTickBox:new(PAD, y + (ROW_HGT - tickHgt) / 2, self.width / 2, tickHgt, "", nil, nil)
    self.tickBox:initialise()
    self.tickBox:instantiate()
    self.tickBox:addOption(getText("UI_Storm_LowRam_DontShowAgain"))
    self.tickBox:setWidthToFit()
    self:addChild(self.tickBox)

    local closeText = getText("UI_Storm_LowRam_Close")
    local closeWid = getTextManager():MeasureStringX(FONT, closeText) + 48
    self.closeButton =
        self:addButton(self.width - PAD - closeWid, y, closeWid, closeText, self.onClose)
end

function Panel:addRichText(text, maxHgt)
    local richText = ISRichTextPanel:new(0, 0, self.width, maxHgt)
    richText:initialise()
    richText.clip = true
    richText.backgroundColor = { r = 0, g = 0, b = 0, a = 0 }
    richText.marginLeft = PAD
    richText.marginRight = PAD
    richText.marginTop = PAD
    self:addChild(richText)
    richText:setText(text)
    richText:paginate()
    if richText:getHeight() > maxHgt then
        richText.autosetheight = false
        richText:setHeight(maxHgt)
        richText:addScrollBars()
    end
    self.richText = richText
    return richText:getHeight()
end

function Panel:addButton(x, y, w, text, handler)
    local button = ISButton:new(x, y, w, ROW_HGT, text, self, handler)
    button:initialise()
    button:instantiate()
    self:addChild(button)
    return button
end

function Panel:addArgsRow(y, labelKey, args)
    local label = ISLabel:new(PAD, y, ROW_HGT, getText(labelKey), 1, 1, 1, 1, FONT, true)
    label:initialise()
    self:addChild(label)

    local x = PAD + LABEL_WID
    local entryWid = self.width - x - PAD - COPY_WID - ROW_GAP
    local entry = ISTextEntryBox:new(args, x, y, entryWid, ROW_HGT)
    entry:initialise()
    entry:instantiate()
    entry:setEditable(false)
    entry:setSelectable(true)
    self:addChild(entry)

    local copy = self:addButton(
        x + entryWid + ROW_GAP,
        y,
        COPY_WID,
        getText("UI_Storm_LowRam_Copy"),
        self.onCopy
    )
    copy.args = args
    table.insert(self.copyButtons, copy)
end

function Panel:onCopy(button)
    Clipboard.setClipboard(button.args)
    button:setTitle(getText("UI_Storm_LowRam_Copied"))
    button.copiedAtMs = getTimestampMs()
end

function Panel:onClose()
    if self.tickBox:isSelected(1) then
        saveDontShowAgain()
    end
    StormRamAllocation.hide()
end

function Panel:update()
    ISPanelJoypad.update(self)
    local now = getTimestampMs()
    for _, button in ipairs(self.copyButtons) do
        if button.copiedAtMs and now - button.copiedAtMs > COPIED_MS then
            button.copiedAtMs = nil
            button:setTitle(getText("UI_Storm_LowRam_Copy"))
        end
    end
end

function Panel:onGainJoypadFocus(joypadData)
    ISPanelJoypad.onGainJoypadFocus(self, joypadData)
    self:setISButtonForB(self.closeButton)
end

function Panel:onLoseJoypadFocus(joypadData)
    ISPanelJoypad.onLoseJoypadFocus(self, joypadData)
    self.closeButton:clearJoypadButton()
end

function Panel:onJoypadDirUp()
    self.richText:setYScroll(self.richText:getYScroll() + JOYPAD_SCROLL_STEP)
end

function Panel:onJoypadDirDown()
    self.richText:setYScroll(self.richText:getYScroll() - JOYPAD_SCROLL_STEP)
end

function Panel:onMouseDown()
    return true
end

function Panel:onMouseUp()
    return true
end

function Panel:onRightMouseDown()
    return true
end

function Panel:onRightMouseUp()
    return true
end

local panel

function StormRamAllocation.show(maxMb, launcherManaged)
    StormRamAllocation.hide()
    panel = Panel:new(maxMb, launcherManaged)
    panel:initialise()
    panel:addToUIManager()
    panel:bringToTop()
    if JoypadState.players[1] then
        panel.prevFocus = getJoypadFocus(0)
        setJoypadFocus(0, panel)
    end
end

function StormRamAllocation.hide()
    if not panel then
        return
    end
    if JoypadState.players[1] and getJoypadFocus(0) == panel then
        setJoypadFocus(0, panel.prevFocus)
    end
    panel:removeFromUIManager()
    panel = nil
end

local function onTick()
    Events.OnTick.Remove(onTick)

    local perf = getPerformanceLocal()
    if not perf then
        return
    end

    local maxMb = perf["memory-max"] or 0
    sendClientCommand(MODULE, COMMAND, {
        maxMb = maxMb,
        totalMb = perf["memory-total"] or 0,
        usedMb = perf["memory-used"] or 0,
        freeMb = perf["memory-free"] or 0,
    })

    local lowRam = maxMb > 0 and maxMb / MB_PER_GIB < LOW_RAM_THRESHOLD_GIB
    if lowRam and not hasStorm() and not loadDontShowAgain() then
        StormRamAllocation.show(maxMb, false)
    end
end

local function onServerCommand(module, command, args)
    if module ~= MODULE or command ~= COMMAND_SHOW_RAM_ALLOC then
        return
    end
    local maxMb = (args and tonumber(args.maxMb)) or 0
    if maxMb <= 0 then
        local perf = getPerformanceLocal()
        maxMb = (perf and perf["memory-max"]) or 0
    end
    if maxMb <= 0 then
        return
    end
    StormRamAllocation.show(maxMb, hasStorm())
end

Events.OnTick.Add(onTick)
Events.OnServerCommand.Add(onServerCommand)
Events.OnMainMenuEnter.Add(StormRamAllocation.hide)
Events.OnDisconnect.Add(StormRamAllocation.hide)
