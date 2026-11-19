--[[
  LucroCraft - UI
  Janela com a tabela de lucro, ordenavel por coluna.
]]

local LC = LucroCraft
local UI = {}
LC.UI = UI

local ROWS, ROW_H = 16, 20
local FRAME_W, FRAME_H = 800, 500

local COLS = {
    { key = "name",    title = "Item",      w = 230, align = "LEFT"  },
    { key = "prof",    title = "Profissao", w = 95,  align = "LEFT"  },
    { key = "cost",    title = "Custo",     w = 85,  align = "RIGHT" },
    { key = "sell",    title = "Venda",     w = 85,  align = "RIGHT" },
    { key = "profit",  title = "Lucro",     w = 90,  align = "RIGHT" },
    { key = "margin",  title = "Margem",    w = 60,  align = "RIGHT" },
    { key = "n",       title = "Leiloes",   w = 55,  align = "RIGHT" },
    { key = "canMake", title = "Fazer",     w = 45,  align = "RIGHT" },
}

local function Hex(r, g, b)
    return string.format("ff%02x%02x%02x", math.floor(r * 255), math.floor(g * 255), math.floor(b * 255))
end

local function QualityHex(q)
    if q and GetItemQualityColor then
        local r, g, b = GetItemQualityColor(q)
        if r then return Hex(r, g, b) end
    end
    return "ffffffff"
end

local function SrcTag(src)
    if src == "vendor" then return " |cff8888ff(NPC)|r" end
    if src == "hist" then return " |cffaaaaaa(hist.)|r" end
    return ""
end

---------------------------------------------------------------------------
-- Construcao da janela
---------------------------------------------------------------------------
local function MakeButton(parent, text, width, onClick)
    local b = CreateFrame("Button", nil, parent, "UIPanelButtonTemplate")
    b:SetWidth(width)
    b:SetHeight(22)
    b:SetText(text)
    b:SetScript("OnClick", onClick)
    return b
end

local function MakeCheck(name, parent, label, getter, setter)
    local c = CreateFrame("CheckButton", name, parent, "UICheckButtonTemplate")
    c:SetWidth(24)
    c:SetHeight(24)
    local text = _G[name .. "Text"]
    if text then text:SetText(label) end
    c:SetScript("OnShow", function(self) self:SetChecked(getter()) end)
    c:SetScript("OnClick", function(self)
        setter(self:GetChecked() and true or false)
        UI:Refresh()
    end)
    return c
end

function UI:Create()
    if self.frame then return end
    local f = CreateFrame("Frame", "LucroCraftFrame", UIParent)
    self.frame = f
    f:SetWidth(FRAME_W)
    f:SetHeight(FRAME_H)
    f:SetPoint("CENTER")
    f:SetFrameStrata("DIALOG")
    f:SetToplevel(true)
    f:SetBackdrop({
        bgFile = "Interface\\DialogFrame\\UI-DialogBox-Background",
        edgeFile = "Interface\\DialogFrame\\UI-DialogBox-Border",
        tile = true, tileSize = 32, edgeSize = 32,
        insets = { left = 11, right = 12, top = 12, bottom = 11 },
    })
    f:EnableMouse(true)
    f:SetMovable(true)
    f:SetClampedToScreen(true)
    f:RegisterForDrag("LeftButton")
    f:SetScript("OnDragStart", f.StartMoving)
    f:SetScript("OnDragStop", f.StopMovingOrSizing)
    f:SetScript("OnShow", function() UI:Refresh() end)
    f:Hide()
    tinsert(UISpecialFrames, "LucroCraftFrame") -- ESC fecha

    local title = f:CreateFontString(nil, "OVERLAY", "GameFontNormalLarge")
    title:SetPoint("TOP", 0, -18)
    title:SetText("|cff33ff99LucroCraft|r - crafts mais lucrativos")

    local close = CreateFrame("Button", nil, f, "UIPanelCloseButton")
    close:SetPoint("TOPRIGHT", -6, -6)

    local summary = f:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
    summary:SetPoint("TOPLEFT", 22, -44)
    summary:SetPoint("TOPRIGHT", -22, -44)
    summary:SetJustifyH("LEFT")
    self.summary = summary

    -- Cabecalhos clicaveis
    local x = 22
    self.headers = {}
    for _, col in ipairs(COLS) do
        local h = CreateFrame("Button", nil, f)
        h:SetWidth(col.w)
        h:SetHeight(18)
        h:SetPoint("TOPLEFT", x, -62)
        local fs = h:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
        fs:SetAllPoints()
        fs:SetJustifyH(col.align)
        h.text = fs
        h:SetHighlightTexture("Interface\\QuestFrame\\UI-QuestTitleHighlight", "ADD")
        h:SetScript("OnClick", function()
            local s = LC.db.settings
            if s.sortKey == col.key then
                s.sortDesc = not s.sortDesc
            else
                s.sortKey = col.key
                s.sortDesc = (col.key ~= "name" and col.key ~= "prof")
            end
            UI:Refresh()
        end)
        h.col = col
        self.headers[#self.headers + 1] = h
        x = x + col.w + 4
    end

    -- Linhas
    self.rows = {}
    for i = 1, ROWS do
        local row = CreateFrame("Button", nil, f)
        row:SetHeight(ROW_H)
        row:SetPoint("TOPLEFT", 18, -82 - (i - 1) * ROW_H)
        row:SetPoint("RIGHT", f, "RIGHT", -40, 0)
        row:SetHighlightTexture("Interface\\QuestFrame\\UI-QuestTitleHighlight", "ADD")
        row:RegisterForClicks("LeftButtonUp", "RightButtonUp")

        if i % 2 == 0 then
            local bg = row:CreateTexture(nil, "BACKGROUND")
            bg:SetAllPoints()
            bg:SetTexture(1, 1, 1, 0.04)
        end

        local icon = row:CreateTexture(nil, "ARTWORK")
        icon:SetWidth(ROW_H - 2)
        icon:SetHeight(ROW_H - 2)
        icon:SetPoint("LEFT", 4, 0)
        row.icon = icon

        row.cells = {}
        local cx = 4
        for c, col in ipairs(COLS) do
            local fs = row:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
            fs:SetHeight(ROW_H)
            fs:SetJustifyH(col.align)
            if c == 1 then
                fs:SetPoint("LEFT", cx + ROW_H + 2, 0)
                fs:SetWidth(col.w - ROW_H - 2)
            else
                fs:SetPoint("LEFT", cx, 0)
                fs:SetWidth(col.w)
            end
            row.cells[c] = fs
            cx = cx + col.w + 4
        end

        row:SetScript("OnEnter", function(self) UI:ShowRowTooltip(self) end)
        row:SetScript("OnLeave", function() GameTooltip:Hide() end)
        row:SetScript("OnClick", function(self, button) UI:OnRowClick(self, button) end)
        self.rows[i] = row
    end

    local scroll = CreateFrame("ScrollFrame", "LucroCraftScroll", f, "FauxScrollFrameTemplate")
    scroll:SetPoint("TOPLEFT", 18, -82)
    scroll:SetPoint("BOTTOMRIGHT", -40, FRAME_H - 82 - ROWS * ROW_H)
    scroll:SetScript("OnVerticalScroll", function(self, offset)
        FauxScrollFrame_OnVerticalScroll(self, offset, ROW_H, function() UI:Update() end)
    end)
    self.scroll = scroll

    -- Rodape: botoes e opcoes
    local bScan = MakeButton(f, "Scan receitas", 110, function() LC.Scanner:Start("targeted") end)
    bScan:SetPoint("BOTTOMLEFT", 20, 50)
    local bFull = MakeButton(f, "Scan completo", 110, function() LC.Scanner:Start("full") end)
    bFull:SetPoint("LEFT", bScan, "RIGHT", 6, 0)
    local bAll = MakeButton(f, "GetAll", 70, function() LC.Scanner:Start("getall") end)
    bAll:SetPoint("LEFT", bFull, "RIGHT", 6, 0)
    local bStop = MakeButton(f, "Parar", 60, function() LC.Scanner:Stop() end)
    bStop:SetPoint("LEFT", bAll, "RIGHT", 6, 0)
    self.scanButtons = { bScan, bFull, bAll }

    local cAll = MakeCheck("LucroCraftCheckAllChars", f, "Todos os personagens",
        function() return LC.db.settings.allChars end,
        function(v) LC.db.settings.allChars = v end)
    cAll:SetPoint("LEFT", bStop, "RIGHT", 14, 0)

    local cHide = MakeCheck("LucroCraftCheckHide", f, "Ocultar sem preco",
        function() return LC.db.settings.hideUnknown end,
        function(v) LC.db.settings.hideUnknown = v end)
    cHide:SetPoint("LEFT", cAll, "RIGHT", 130, 0)

    local bMode = MakeButton(f, "", 120, function()
        local s = LC.db.settings
        s.sellMode = (s.sellMode == "min") and "market" or "min"
        UI:Refresh()
    end)
    bMode:SetPoint("BOTTOMRIGHT", -20, 50)
    self.modeButton = bMode

    local status = f:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
    status:SetPoint("BOTTOMLEFT", 22, 32)
    status:SetPoint("BOTTOMRIGHT", -22, 32)
    status:SetJustifyH("LEFT")
    self.status = status

    local hint = f:CreateFontString(nil, "OVERLAY", "GameFontDisableSmall")
    hint:SetPoint("BOTTOMLEFT", 22, 16)
    hint:SetText("Clique: buscar na AH  ||  Shift+clique: link no chat  ||  Botao direito: lista de compras")
    self.hint = hint

    self:SetStatus(LC.Scanner.statusText or "Abra a Casa de Leiloes e clique em \"Scan receitas\".")
end

---------------------------------------------------------------------------
-- Atualizacao
---------------------------------------------------------------------------
function UI:Toggle()
    self:Create()
    if self.frame:IsShown() then self.frame:Hide() else self.frame:Show() end
end

function UI:SetStatus(text)
    if self.status then self.status:SetText(text or "") end
end

function UI:Refresh()
    if not self.frame or not self.frame:IsShown() then return end
    self.data = LC:BuildResults()

    -- cabecalhos com indicador de ordenacao
    local s = LC.db.settings
    for _, h in ipairs(self.headers) do
        local t = h.col.title
        if s.sortKey == h.col.key then t = t .. (s.sortDesc and " v" or " ^") end
        h.text:SetText(t)
    end
    self.modeButton:SetText(s.sellMode == "min" and "Venda: menor preco" or "Venda: mercado")

    -- resumo
    local profs = {}
    local char = LC.db.chars[LC:CharKey()]
    if char then
        for name, p in pairs(char.profs) do
            local n = 0
            for _ in pairs(p.recipes) do n = n + 1 end
            profs[#profs + 1] = string.format("%s %s/%s (%d)", name, p.rank or "?", p.max or "?", n)
        end
    end
    local count = 0
    for _, row in ipairs(self.data) do
        if row.profit and row.profit > 0 then count = count + 1 end
    end
    self.summary:SetText(string.format(
        "Profissoes: %s   ||   Ultimo scan: %s   ||   Corte AH: %d%%   ||   %d receitas, %d com lucro",
        #profs > 0 and table.concat(profs, ", ") or "|cffff8080nenhuma - abra a janela da profissao|r",
        LC.Ago(LC.db.lastScan), math.floor(s.ahCut * 100 + 0.5), #self.data, count))

    self:Update()
end

function UI:Update()
    local data = self.data or {}
    FauxScrollFrame_Update(self.scroll, #data, ROWS, ROW_H)
    local offset = FauxScrollFrame_GetOffset(self.scroll)
    for i = 1, ROWS do
        local row = self.rows[i]
        local d = data[i + offset]
        if d then
            row.data = d
            local _, _, quality, _, _, _, _, _, _, texture = GetItemInfo(d.id)
            row.icon:SetTexture(texture or "Interface\\Icons\\INV_Misc_QuestionMark")
            local c = row.cells
            local made = d.made > 1 and string.format(" |cffaaaaaax%g|r", d.made) or ""
            c[1]:SetText("|c" .. QualityHex(quality) .. (d.name or "?") .. "|r" .. made)
            c[2]:SetText(LC.db.settings.allChars and (d.prof .. " |cff888888" .. d.char .. "|r") or d.prof)
            c[3]:SetText(LC.Money(d.cost))
            c[4]:SetText(d.sell and (LC.Money(d.sell) .. (d.sellSrc == "hist" and "|cffaaaaaa*|r" or "")) or LC.Money(nil))
            if d.profit then
                c[5]:SetText((d.profit >= 0 and "|cff40ff40+|r" or "") .. LC.Money(d.profit))
            else
                c[5]:SetText(LC.Money(nil))
            end
            if d.margin then
                local color = d.margin >= 0 and "|cff40ff40" or "|cffff4040"
                c[6]:SetText(string.format("%s%d%%|r", color, math.floor(d.margin * 100 + 0.5)))
            else
                c[6]:SetText("|cff808080-|r")
            end
            if d.n == nil then
                c[7]:SetText("|cff808080-|r")
            elseif d.n == 0 then
                c[7]:SetText("|cff40ff400|r") -- ninguem vendendo: oportunidade (ou item sem procura)
            else
                c[7]:SetText(tostring(d.n))
            end
            c[8]:SetText(d.canMake > 0 and ("|cff40ff40" .. d.canMake .. "|r") or "|cff8080800|r")
            row:Show()
        else
            row.data = nil
            row:Hide()
        end
    end
end

---------------------------------------------------------------------------
-- Tooltip detalhado de uma linha
---------------------------------------------------------------------------
function UI:ShowRowTooltip(row)
    local d = row.data
    if not d then return end
    local tt = GameTooltip
    tt:SetOwner(row, "ANCHOR_RIGHT")
    local _, link, quality = GetItemInfo(d.id)
    tt:AddLine("|c" .. QualityHex(quality) .. (d.name or "?") .. "|r")
    tt:AddLine(string.format("%s - %s%s", d.prof, d.char,
        d.made ~= 1 and string.format(" - produz em media %g por craft", d.made) or ""), 0.7, 0.7, 0.7)
    tt:AddLine(" ")
    tt:AddLine("Materiais (por craft):", 1, 0.82, 0)
    for _, r in ipairs(d.reagents) do
        local have = GetItemCount(r.id) or 0
        local left = string.format("%dx %s |cff888888(tem %d)|r", r.count, LC:ItemName(r.id), have)
        local right = r.price and (LC.Money(r.price * r.count) .. SrcTag(r.src)) or "|cffff4040sem preco|r"
        tt:AddDoubleLine(left, right, 1, 1, 1, 1, 1, 1)
    end
    tt:AddLine(" ")
    tt:AddDoubleLine("Custo total:", LC.Money(d.cost), 1, 0.82, 0, 1, 1, 1)
    if d.sell then
        tt:AddDoubleLine(string.format("Venda (%g x %s):", d.made, LC.Money(d.sell)),
            LC.Money(d.sell * d.made) .. SrcTag(d.sellSrc), 1, 0.82, 0, 1, 1, 1)
        tt:AddDoubleLine(string.format("Corte da AH (%d%%):", math.floor(LC.db.settings.ahCut * 100 + 0.5)),
            LC.Money(-(d.sell * d.made - d.revenue)), 1, 0.82, 0, 1, 1, 1)
    else
        tt:AddDoubleLine("Venda:", "|cffff4040sem dados de preco|r", 1, 0.82, 0)
    end
    tt:AddDoubleLine("Lucro:", LC.Money(d.profit), 1, 0.82, 0, 1, 1, 1)
    tt:AddLine(" ")

    local p = LC.db.prices[d.id]
    if p and p.qty and p.qty > 0 then
        tt:AddDoubleLine("Na AH:", string.format("%d leiloes, %d unidades", p.n, p.qty), 0.7, 0.7, 0.7, 1, 1, 1)
        tt:AddDoubleLine("Menor buyout / mercado:", LC.Money(p.min) .. " / " .. LC.Money(p.mkt), 0.7, 0.7, 0.7, 1, 1, 1)
        if d.mine and d.mine > 0 then
            tt:AddLine(string.format("Voce ja tem %d leilao(oes) deste item.", d.mine), 0.5, 0.8, 1)
        end
    elseif p and p.t then
        tt:AddLine("Nenhum leilao deste item no ultimo scan.", 0.4, 1, 0.4)
        tt:AddLine("Sem concorrencia - mas confirme se ha procura!", 0.7, 0.7, 0.7)
    else
        tt:AddLine("Item ainda nao escaneado.", 0.7, 0.7, 0.7)
    end
    if d.trend then
        local pct = math.floor(d.trend * 100 + 0.5)
        if pct >= 10 then
            tt:AddLine(string.format("Preco %d%% ACIMA da media recente: bom momento p/ vender.", pct), 0.4, 1, 0.4)
        elseif pct <= -10 then
            tt:AddLine(string.format("Preco %d%% ABAIXO da media recente: talvez segurar.", -pct), 1, 0.5, 0.3)
        end
    end
    if d.npc and d.revenue and d.npc > d.revenue then
        tt:AddLine("Vender ao NPC rende mais (" .. LC.Money(d.npc) .. ")!", 1, 0.5, 1)
    end
    if d.scanned then
        tt:AddDoubleLine("Atualizado:", LC.Ago(d.scanned), 0.5, 0.5, 0.5, 0.5, 0.5, 0.5)
    end
    tt:Show()
end

---------------------------------------------------------------------------
-- Cliques
---------------------------------------------------------------------------
local function SearchAH(name)
    if not (AuctionFrame and AuctionFrame:IsShown() and BrowseName) then
        LC:Print("Abra a Casa de Leiloes para buscar.")
        return
    end
    if AuctionFrameTab1 and AuctionFrameTab_OnClick then AuctionFrameTab_OnClick(AuctionFrameTab1) end
    BrowseName:SetText(name)
    if AuctionFrameBrowse_Search then AuctionFrameBrowse_Search() end
end

function UI:OnRowClick(row, button)
    local d = row.data
    if not d then return end
    local _, link = GetItemInfo(d.id)
    if button == "RightButton" then
        -- lista de compras para 1 craft (ou para quantos voce pedir no futuro)
        LC:Print("Lista de compras para %s:", d.name)
        for _, r in ipairs(d.reagents) do
            local have = GetItemCount(r.id) or 0
            local need = math.max(0, r.count - have)
            LC:Print("  %dx %s  (tem %d, falta %d)  %s", r.count, LC:ItemName(r.id), have, need,
                r.price and LC.Money(r.price * need) .. SrcTag(r.src) or "?")
        end
    elseif IsShiftKeyDown() and link then
        if ChatEdit_InsertLink then
            ChatEdit_InsertLink(link)
        elseif ChatFrameEditBox and ChatFrameEditBox:IsVisible() then
            ChatFrameEditBox:Insert(link)
        end
    else
        SearchAH(d.name)
    end
end

---------------------------------------------------------------------------
-- Botao dentro da janela da Casa de Leiloes
---------------------------------------------------------------------------
function UI:AttachToAuctionHouse()
    if self.ahButton or not AuctionFrame then return end
    local b = CreateFrame("Button", "LucroCraftAHButton", AuctionFrame, "UIPanelButtonTemplate")
    b:SetWidth(90)
    b:SetHeight(22)
    b:SetText("LucroCraft")
    b:SetPoint("TOPRIGHT", AuctionFrame, "TOPRIGHT", -40, -12)
    b:SetScript("OnClick", function() UI:Toggle() end)
    self.ahButton = b
end
