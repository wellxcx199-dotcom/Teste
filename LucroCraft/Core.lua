--[[
  LucroCraft - Core
  Banco de dados, utilitarios, eventos, leitura das profissoes,
  aprendizado de precos de vendedor e linhas extras nos tooltips.
  Compativel com o cliente 3.3.5a (Interface 30300, Lua 5.1).
]]

LucroCraft = LucroCraft or {}
local LC = LucroCraft

LC.version = "1.0.0"
LC.HISTORY_DAYS = 14

local floor, max, min = math.floor, math.max, math.min

---------------------------------------------------------------------------
-- Valores padrao
---------------------------------------------------------------------------
local DEFAULT_SETTINGS = {
    ahCut      = 0.05,     -- corte da AH (5% na AH da faccao, 15% na neutra/goblin)
    sellMode   = "market", -- "market" (media dos 25% mais baratos) ou "min" (menor buyout)
    allChars   = false,    -- mostrar receitas de todos os personagens (alts)
    hideUnknown = false,   -- esconder linhas sem preco suficiente para calcular lucro
    sortKey    = "profit",
    sortDesc   = true,
}

-- Precos de vendedor conhecidos (em cobre). O addon aprende o resto sozinho
-- sempre que voce abre um vendedor; estes sao so um ponto de partida.
local SEED_VENDOR = {
    [2320]  = 10,    -- Coarse Thread
    [2321]  = 100,   -- Fine Thread
    [4291]  = 500,   -- Silken Thread
    [8343]  = 2000,  -- Heavy Silken Thread
    [14341] = 5000,  -- Rune Thread
}

---------------------------------------------------------------------------
-- Utilitarios
---------------------------------------------------------------------------
function LC:Print(msg, ...)
    if select("#", ...) > 0 then msg = string.format(msg, ...) end
    DEFAULT_CHAT_FRAME:AddMessage("|cff33ff99LucroCraft:|r " .. tostring(msg))
end

-- Extrai o itemID de um link de item ("|Hitem:12345:...|h")
function LC.ID(link)
    if type(link) ~= "string" then return nil end
    local id = link:match("item:(%d+)")
    return id and tonumber(id) or nil
end

-- Formata cobre como "12g 34s", "5s 07c" ou "42c"
function LC.Money(c)
    if not c then return "|cff808080?|r" end
    local neg = c < 0
    c = floor(math.abs(c) + 0.5)
    local g = floor(c / 10000)
    local s = floor((c % 10000) / 100)
    local cp = c % 100
    local str
    if g > 0 then
        str = string.format("%d|cffffd700g|r %02d|cffc7c7cfs|r", g, s)
    elseif s > 0 then
        str = string.format("%d|cffc7c7cfs|r %02d|cffeda55fc|r", s, cp)
    else
        str = string.format("%d|cffeda55fc|r", cp)
    end
    if neg then str = "|cffff4040-|r" .. str end
    return str
end

-- "ha 5 min", "ha 3 h", "ha 2 dias"
function LC.Ago(t)
    if not t then return "nunca" end
    local d = time() - t
    if d < 60 then return "agora" end
    if d < 3600 then return string.format("ha %d min", floor(d / 60)) end
    if d < 86400 then return string.format("ha %d h", floor(d / 3600)) end
    return string.format("ha %d dias", floor(d / 86400))
end

function LC.Today()
    return floor(time() / 86400)
end

function LC:CharKey()
    return (UnitName("player") or "?") .. " - " .. (GetRealmName() or "?")
end

function LC:CharData(key)
    key = key or self:CharKey()
    local c = self.db.chars[key]
    if not c then
        c = { profs = {} }
        self.db.chars[key] = c
    end
    return c
end

function LC:ItemName(id)
    local name = GetItemInfo(id)
    if name then
        self.db.names[id] = name
        return name
    end
    return self.db.names[id] or ("item:" .. id)
end

---------------------------------------------------------------------------
-- Temporizadores simples (3.3.5 nao tem C_Timer)
---------------------------------------------------------------------------
local debounces = {}

-- Executa fn depois de "delay" segundos; chamadas repetidas com a mesma
-- chave reiniciam a contagem (util para eventos que disparam em rajada).
function LC:Debounce(key, delay, fn)
    debounces[key] = { t = GetTime() + delay, f = fn }
end

function LC:CancelDebounce(key)
    debounces[key] = nil
end

local function RunDebounces()
    local now, due = GetTime(), nil
    for k, d in pairs(debounces) do
        if now >= d.t then
            due = due or {}
            due[#due + 1] = d.f
            debounces[k] = nil
        end
    end
    if due then
        for _, f in ipairs(due) do f() end
    end
end

---------------------------------------------------------------------------
-- Banco de dados
---------------------------------------------------------------------------
function LC:InitDB()
    LucroCraftDB = LucroCraftDB or {}
    local db = LucroCraftDB
    db.prices   = db.prices or {}   -- [itemID] = { min, mkt, qty, n, mine, t }
    db.history  = db.history or {}  -- [itemID] = { [dia] = precoMercado }
    db.names    = db.names or {}    -- [itemID] = nome
    db.vendor   = db.vendor or {}   -- [itemID] = preco de compra no NPC (por unidade)
    db.chars    = db.chars or {}    -- [personagem] = { profs = { [prof] = {rank, max, recipes} } }
    db.settings = db.settings or {}
    for k, v in pairs(DEFAULT_SETTINGS) do
        if db.settings[k] == nil then db.settings[k] = v end
    end
    for id, price in pairs(SEED_VENDOR) do
        if not db.vendor[id] then db.vendor[id] = price end
    end
    self.db = db
    self:PruneHistory()
end

function LC:PruneHistory()
    local limit = self.Today() - self.HISTORY_DAYS
    for id, days in pairs(self.db.history) do
        local any = false
        for day in pairs(days) do
            if day < limit then days[day] = nil else any = true end
        end
        if not any then self.db.history[id] = nil end
    end
end

-- Media historica (ultimos 14 dias) do preco de mercado de um item
function LC:HistAvg(id, excludeToday)
    local days = self.db.history[id]
    if not days then return nil end
    local today, sum, n = self.Today(), 0, 0
    for day, price in pairs(days) do
        if not (excludeToday and day == today) then
            sum, n = sum + price, n + 1
        end
    end
    if n == 0 then return nil end
    return sum / n, n
end

---------------------------------------------------------------------------
-- Leitura das profissoes (janela de profissao aberta)
---------------------------------------------------------------------------
function LC:RecordTradeSkill()
    if IsTradeSkillLinked and IsTradeSkillLinked() then return end -- receita de outro jogador
    local prof, rank, maxRank = GetTradeSkillLine()
    if not prof or prof == "UNKNOWN" then return end
    local num = GetNumTradeSkills()
    if not num or num == 0 then return end

    local char = self:CharData()
    local p = char.profs[prof]
    if not p then
        p = { recipes = {} }
        char.profs[prof] = p
    end
    p.rank, p.max = rank, maxRank

    local added, pending = 0, 0
    for i = 1, num do
        local skillName, skillType = GetTradeSkillInfo(i)
        if skillName and skillType ~= "header" then
            -- Encantamentos retornam link "enchant:" (nao e item vendavel) e sao ignorados.
            local pid = LC.ID(GetTradeSkillItemLink(i))
            if pid then
                local numReag = GetTradeSkillNumReagents(i) or 0
                local reagents, complete = {}, numReag > 0
                for j = 1, numReag do
                    local rName, _, rCount = GetTradeSkillReagentInfo(i, j)
                    local rid = LC.ID(GetTradeSkillReagentItemLink(i, j))
                    if rid and rCount then
                        reagents[rid] = (reagents[rid] or 0) + rCount
                        if rName then self.db.names[rid] = rName end
                    else
                        complete = false -- item ainda nao esta no cache; tentamos de novo depois
                    end
                end
                if complete then
                    local minMade, maxMade = GetTradeSkillNumMade(i)
                    minMade = minMade or 1
                    maxMade = maxMade or minMade
                    p.recipes[pid] = {
                        r = reagents,
                        n = (minMade + maxMade) / 2,
                        k = skillType,
                        s = skillName,
                    }
                    self.db.names[pid] = self.db.names[pid] or skillName
                    added = added + 1
                else
                    pending = pending + 1
                end
            end
        end
    end

    self.recipeIndex = nil
    if pending > 0 and (self.tradeRetries or 0) < 3 then
        self.tradeRetries = (self.tradeRetries or 0) + 1
        self:Debounce("trade", 1.5, function() LC:RecordTradeSkill() end)
    elseif added > 0 and not self.tradeAnnounced then
        self.tradeAnnounced = true
        self:Print("%s: %d receitas registradas.", prof, added)
    end
    if self.UI then self.UI:Refresh() end
end

-- Indice produto -> receita (usado pelo tooltip)
function LC:FindRecipe(pid)
    if not self.recipeIndex then
        local idx = {}
        for _, c in pairs(self.db.chars) do
            for prof, p in pairs(c.profs) do
                for id, r in pairs(p.recipes) do
                    if not idx[id] then idx[id] = r end
                end
            end
        end
        self.recipeIndex = idx
    end
    return self.recipeIndex[pid]
end

---------------------------------------------------------------------------
-- Aprende precos de vendedor quando voce abre um NPC
---------------------------------------------------------------------------
function LC:RecordMerchant()
    local learned = 0
    for i = 1, GetMerchantNumItems() do
        local _, _, price, quantity, numAvailable, _, extendedCost = GetMerchantItemInfo(i)
        local id = LC.ID(GetMerchantItemLink(i))
        -- So itens com estoque ilimitado e pagos em ouro (sem tokens/honra)
        if id and price and price > 0 and numAvailable == -1 and not extendedCost then
            local unit = price / max(quantity or 1, 1)
            if self.db.vendor[id] ~= unit then learned = learned + 1 end
            self.db.vendor[id] = unit
        end
    end
    if learned > 0 and self.UI then self.UI:Refresh() end
end

---------------------------------------------------------------------------
-- Linhas extras nos tooltips de itens
---------------------------------------------------------------------------
local function OnTooltipSetItem(tt)
    if not LC.db then return end
    local _, link = tt:GetItem()
    local id = LC.ID(link)
    if not id then return end

    local p = LC.db.prices[id]
    if p and p.qty and p.qty > 0 and p.mkt then
        tt:AddDoubleLine("|cff33ff99LucroCraft|r AH (mercado):",
            LC.Money(p.mkt) .. string.format(" |cffaaaaaa(%d un.)|r", p.qty), 1, 1, 1, 1, 1, 1)
    elseif p and p.t then
        tt:AddDoubleLine("|cff33ff99LucroCraft|r AH:", "|cffaaaaaanenhum leilao|r")
    end
    local hist, n = LC:HistAvg(id)
    if hist and n and n > 1 then
        tt:AddDoubleLine("   media " .. n .. " dias:", LC.Money(hist), 0.7, 0.7, 0.7, 1, 1, 1)
    end
    local cost = LC:RecipeCost(id)
    if cost then
        tt:AddDoubleLine("   custo de craft:", LC.Money(cost), 0.7, 0.7, 0.7, 1, 1, 1)
    end
end

---------------------------------------------------------------------------
-- Eventos
---------------------------------------------------------------------------
local frame = CreateFrame("Frame", "LucroCraftEventFrame")
LC.frame = frame

local events = {}

function events:ADDON_LOADED(name)
    if name ~= "LucroCraft" then return end
    LC:InitDB()
    GameTooltip:HookScript("OnTooltipSetItem", OnTooltipSetItem)
    if ItemRefTooltip then ItemRefTooltip:HookScript("OnTooltipSetItem", OnTooltipSetItem) end
    frame:UnregisterEvent("ADDON_LOADED")
end

function events:PLAYER_LOGIN()
    LC:CharData() -- garante a entrada do personagem
    LC:Print("v%s carregado. Digite |cffffff00/lucro|r para abrir.", LC.version)
end

function events:TRADE_SKILL_SHOW()
    LC.tradeRetries, LC.tradeAnnounced = 0, false
    -- Expande todas as categorias para enxergar todas as receitas
    if ExpandTradeSkillSubClass then ExpandTradeSkillSubClass(0) end
    LC:Debounce("trade", 0.5, function() LC:RecordTradeSkill() end)
end

function events:TRADE_SKILL_UPDATE()
    LC:Debounce("trade", 0.5, function() LC:RecordTradeSkill() end)
end

function events:TRADE_SKILL_CLOSE()
    LC:CancelDebounce("trade")
end

function events:MERCHANT_SHOW()
    LC:Debounce("merchant", 0.3, function() LC:RecordMerchant() end)
end

function events:AUCTION_HOUSE_SHOW()
    LC.ahOpen = true
    if LC.UI then LC.UI:AttachToAuctionHouse() end
end

function events:AUCTION_HOUSE_CLOSED()
    LC.ahOpen = false
    if LC.Scanner then LC.Scanner:Stop(true) end
end

function events:AUCTION_ITEM_LIST_UPDATE()
    if LC.Scanner then LC.Scanner:OnListUpdate() end
end

function events:BAG_UPDATE()
    -- a coluna "Fazer" depende do que esta na bolsa
    if LC.UI and LC.UI.frame and LC.UI.frame:IsShown() then
        LC:Debounce("bags", 1, function() LC.UI:Refresh() end)
    end
end

frame:SetScript("OnEvent", function(_, event, ...)
    local handler = events[event]
    if handler then handler(events, ...) end
end)
for event in pairs(events) do frame:RegisterEvent(event) end

frame:SetScript("OnUpdate", function(_, elapsed)
    RunDebounces()
    if LC.Scanner and LC.Scanner.state then LC.Scanner:OnUpdate(elapsed) end
end)

---------------------------------------------------------------------------
-- Comandos
---------------------------------------------------------------------------
SLASH_LUCROCRAFT1 = "/lucro"
SLASH_LUCROCRAFT2 = "/lc"
SlashCmdList["LUCROCRAFT"] = function(msg)
    msg = (msg or ""):lower():gsub("^%s+", ""):gsub("%s+$", "")
    local cmd, arg = msg:match("^(%S*)%s*(.-)$")
    local s = LC.db.settings

    if cmd == "" then
        LC.UI:Toggle()
    elseif cmd == "scan" then
        LC.Scanner:Start("targeted")
    elseif cmd == "full" then
        LC.Scanner:Start("full")
    elseif cmd == "getall" then
        LC.Scanner:Start("getall")
    elseif cmd == "stop" or cmd == "parar" then
        LC.Scanner:Stop()
    elseif cmd == "corte" then
        local v = tonumber(arg)
        if v and v >= 0 and v < 100 then
            s.ahCut = v / 100
            LC:Print("Corte da AH definido para %d%%.", v)
            LC.UI:Refresh()
        else
            LC:Print("Uso: /lucro corte 5   (use 15 para a AH neutra)")
        end
    elseif cmd == "modo" then
        if arg == "min" then s.sellMode = "min" elseif arg == "mercado" then s.sellMode = "market" end
        LC:Print("Preco de venda usando: %s", s.sellMode == "min" and "menor buyout" or "preco de mercado")
        LC.UI:Refresh()
    elseif cmd == "vendor" then
        -- /lucro vendor <itemID> <preco em cobre>  (registro manual)
        local id, price = arg:match("^(%d+)%s+(%d+)$")
        if id then
            LC.db.vendor[tonumber(id)] = tonumber(price)
            LC:Print("Preco de vendedor de %s = %s", LC:ItemName(tonumber(id)), LC.Money(tonumber(price)))
            LC.UI:Refresh()
        else
            LC:Print("Uso: /lucro vendor <itemID> <preco em cobre>")
        end
    elseif cmd == "reset" then
        if arg == "precos" then
            wipe(LC.db.prices); wipe(LC.db.history)
            LC:Print("Precos e historico apagados.")
        elseif arg == "receitas" then
            LC:CharData().profs = {}
            LC.recipeIndex = nil
            LC:Print("Receitas deste personagem apagadas. Abra suas profissoes de novo.")
        else
            LC:Print("Uso: /lucro reset precos  ou  /lucro reset receitas")
        end
        LC.UI:Refresh()
    else
        LC:Print("Comandos:")
        LC:Print("  /lucro - abre/fecha a janela")
        LC:Print("  /lucro scan - escaneia so os itens das suas receitas (AH aberta)")
        LC:Print("  /lucro full - escaneia a AH inteira, pagina por pagina")
        LC:Print("  /lucro getall - scan instantaneo (1x a cada 15 min, se o servidor permitir)")
        LC:Print("  /lucro stop - interrompe o scan")
        LC:Print("  /lucro corte <n> - corte da AH em porcentagem (padrao 5, neutra 15)")
        LC:Print("  /lucro modo min  ou  /lucro modo mercado - qual preco usar para vender")
        LC:Print("  /lucro vendor <itemID> <cobre> - registra preco de NPC manualmente")
        LC:Print("  /lucro reset precos  ou  /lucro reset receitas")
    end
end
