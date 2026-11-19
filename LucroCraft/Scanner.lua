--[[
  LucroCraft - Scanner
  Tres modos de scan da Casa de Leiloes:

  targeted : procura pelo nome apenas os produtos e materiais das suas
             receitas. Rapido e e o modo recomendado no dia a dia.
  full     : percorre a AH inteira pagina por pagina (50 leiloes/pagina).
  getall   : pede a AH inteira de uma vez (permitido 1x a cada 15 min e
             nem todo servidor privado libera). O processamento e feito em
             pedacos por frame para nao travar/desconectar o cliente.
]]

local LC = LucroCraft
local S = { state = nil }
LC.Scanner = S

local PAGE_SIZE = NUM_AUCTION_ITEMS_PER_PAGE or 50
local MAX_PAGES_PER_ITEM = 5     -- scan direcionado: no maximo 250 leiloes por item
local GETALL_CHUNK = 400         -- leiloes processados por frame no getall
local SETTLE_DELAY = 0.3         -- espera para os dados da pagina "assentarem"
local MAX_SETTLE_TRIES = 6
local QUERY_TIMEOUT = 15

local floor, max, min = math.floor, math.max, math.min

local function Status(text)
    S.statusText = text
    if LC.UI then LC.UI:SetStatus(text) end
end

---------------------------------------------------------------------------
-- Controle
---------------------------------------------------------------------------
function S:Start(mode)
    if not LC.ahOpen then
        LC:Print("Abra a Casa de Leiloes primeiro.")
        return
    end
    if self.state then
        LC:Print("Ja existe um scan em andamento (/lucro stop para parar).")
        return
    end

    self.mode = mode
    self.data = {}       -- [itemID] = { {precoUnitario, quantidade, ehMeu}, ... }
    self.queried = {}    -- itens procurados no scan direcionado
    self.page = 0
    self.missingTotal = 0
    self.me = UnitName("player")
    self.startedAt = GetTime()

    if mode == "getall" then
        local _, canQueryAll = CanSendAuctionQuery()
        if not canQueryAll then
            LC:Print("GetAll indisponivel agora (so 1x a cada 15 min, ou o servidor nao permite). Use o scan normal.")
            return
        end
        self.state = "getall_wait"
        self.sentAt = GetTime()
        QueryAuctionItems("", "", "", nil, nil, nil, 0, nil, -1, true)
        Status("GetAll: aguardando o servidor (pode levar alguns segundos)...")
    elseif mode == "full" then
        self.state = "send"
        Status("Scan completo: iniciando...")
    elseif mode == "targeted" then
        self.queue = LC:TargetItems()
        if #self.queue == 0 then
            LC:Print("Nenhuma receita registrada. Abra a janela de cada profissao uma vez e tente de novo.")
            return
        end
        self.qi = 1
        self.state = "send"
        Status(string.format("Scan das receitas: 0/%d itens", #self.queue))
    end
end

function S:Stop(silent)
    if not self.state then return end
    local hadData = self.data and next(self.data)
    self.state = nil
    if hadData then
        self:Finish(true) -- salva o que ja foi coletado
    else
        Status("Scan interrompido.")
    end
    if not silent then LC:Print("Scan interrompido.") end
end

---------------------------------------------------------------------------
-- Envio de consultas
---------------------------------------------------------------------------
function S:SendQuery()
    if self.mode == "full" then
        QueryAuctionItems("", "", "", nil, nil, nil, self.page, nil, -1, false)
    else
        local item = self.queue[self.qi]
        self.queried[item.id] = true
        QueryAuctionItems(item.name, "", "", nil, nil, nil, self.page, nil, -1, false)
    end
    self.state = "wait"
    self.sentAt = GetTime()
end

function S:OnListUpdate()
    if self.state == "wait" then
        self.state = "settle"
        self.settleAt = GetTime() + SETTLE_DELAY
        self.settleTries = 0
    elseif self.state == "getall_wait" then
        self.state = "getall_read"
        self.idx = 1
        self.total = GetNumAuctionItems("list") or 0
        if self.total == 0 then
            self:Finish()
        end
    end
end

---------------------------------------------------------------------------
-- Leitura dos leiloes
---------------------------------------------------------------------------
local function CountMissing(first, last)
    local missing = 0
    for i = first, last do
        if not GetAuctionItemLink("list", i) then missing = missing + 1 end
    end
    return missing
end

-- Le os leiloes [first..last]; se filterID for passado, guarda so esse item
function S:ReadRange(first, last, filterID)
    local data, me, names = self.data, self.me, LC.db.names
    local missing = 0
    for i = first, last do
        local name, _, count, _, _, _, _, _, buyout, _, _, owner = GetAuctionItemInfo("list", i)
        local id = LC.ID(GetAuctionItemLink("list", i))
        if id then
            if name then names[id] = name end
            if (not filterID or id == filterID) and buyout and buyout > 0 and count and count > 0 then
                local t = data[id]
                if not t then t = {}; data[id] = t end
                t[#t + 1] = { buyout / count, count, owner ~= nil and owner == me }
            end
        else
            missing = missing + 1
        end
    end
    return missing
end

---------------------------------------------------------------------------
-- Maquina de estados (chamada a cada frame pelo Core)
---------------------------------------------------------------------------
function S:OnUpdate()
    local now = GetTime()
    local st = self.state

    if st == "send" then
        if CanSendAuctionQuery() then self:SendQuery() end

    elseif st == "wait" then
        if now - self.sentAt > QUERY_TIMEOUT then
            self.state = "send" -- o servidor nao respondeu; tenta de novo
        end

    elseif st == "settle" then
        if now < self.settleAt then return end
        local batch = GetNumAuctionItems("list") or 0
        -- espera os links (cache de itens) chegarem antes de ler a pagina
        if batch > 0 and CountMissing(1, batch) > 0 and self.settleTries < MAX_SETTLE_TRIES then
            self.settleTries = self.settleTries + 1
            self.settleAt = now + SETTLE_DELAY
            return
        end
        self:ProcessPage()

    elseif st == "getall_wait" then
        if now - self.sentAt > 120 then
            LC:Print("O servidor nao respondeu ao GetAll. Tente o scan normal.")
            self.state = nil
            Status("GetAll falhou.")
        end

    elseif st == "getall_read" then
        local last = min(self.idx + GETALL_CHUNK - 1, self.total)
        self.missingTotal = self.missingTotal + self:ReadRange(self.idx, last)
        self.idx = last + 1
        Status(string.format("GetAll: processando %d/%d leiloes", last, self.total))
        if self.idx > self.total then self:Finish() end
    end
end

function S:ProcessPage()
    local batch, total = GetNumAuctionItems("list")
    batch, total = batch or 0, total or 0

    if self.mode == "full" then
        self.missingTotal = self.missingTotal + self:ReadRange(1, batch)
        local pages = max(1, math.ceil(total / PAGE_SIZE))
        Status(string.format("Scan completo: pagina %d/%d", self.page + 1, pages))
        if (self.page + 1) * PAGE_SIZE < total then
            self.page = self.page + 1
            self.state = "send"
        else
            self:Finish()
        end
    else
        local item = self.queue[self.qi]
        self.missingTotal = self.missingTotal + self:ReadRange(1, batch, item.id)
        if (self.page + 1) * PAGE_SIZE < total and self.page + 1 < MAX_PAGES_PER_ITEM then
            self.page = self.page + 1
        else
            self.qi = self.qi + 1
            self.page = 0
        end
        Status(string.format("Scan das receitas: %d/%d itens", min(self.qi - 1, #self.queue), #self.queue))
        if self.qi > #self.queue then
            self:Finish()
        else
            self.state = "send"
        end
    end
end

---------------------------------------------------------------------------
-- Estatisticas e gravacao
---------------------------------------------------------------------------

-- Preco de "mercado": media ponderada das unidades mais baratas que somam
-- 25% da quantidade total. Isso ignora um leilao isolado muito barato (ou
-- muito caro) e reflete o preco que voce realmente pagaria comprando um lote.
local function ComputeStats(list)
    table.sort(list, function(a, b) return a[1] < b[1] end)
    local qty, mine = 0, 0
    for _, a in ipairs(list) do
        qty = qty + a[2]
        if a[3] then mine = mine + 1 end
    end
    local target = max(1, qty * 0.25)
    local acc, sum = 0, 0
    for _, a in ipairs(list) do
        local take = min(a[2], target - acc)
        sum = sum + take * a[1]
        acc = acc + take
        if acc >= target then break end
    end
    return {
        min = list[1][1],
        mkt = sum / acc,
        qty = qty,
        n = #list,
        mine = mine,
    }
end

function S:Finish(partial)
    local db = LC.db
    local now, today = time(), LC.Today()
    local updated = 0

    for id, list in pairs(self.data) do
        local stats = ComputeStats(list)
        stats.t = now
        db.prices[id] = stats
        local h = db.history[id]
        if not h then h = {}; db.history[id] = h end
        h[today] = stats.mkt
        updated = updated + 1
    end

    -- Itens que nao apareceram: nao ha leiloes deles agora. Mantemos o
    -- ultimo preco conhecido (para o historico), mas zeramos a oferta.
    if not partial then
        local function MarkEmpty(id)
            local p = db.prices[id]
            if not p then p = {}; db.prices[id] = p end
            p.qty, p.n, p.mine, p.t = 0, 0, 0, now
        end
        if self.mode == "targeted" then
            for id in pairs(self.queried) do
                if not self.data[id] then MarkEmpty(id) end
            end
        else
            for id in pairs(db.prices) do
                if not self.data[id] then MarkEmpty(id) end
            end
        end
        db.lastScan = now
    end

    self.state = nil
    local secs = floor(GetTime() - (self.startedAt or GetTime()))
    local msg = string.format("Scan %s: %d itens com preco em %ds.",
        partial and "parcial salvo" or "concluido", updated, secs)
    if self.missingTotal > 0 then
        msg = msg .. string.format(" (%d leiloes sem dados do item foram ignorados)", self.missingTotal)
    end
    LC:Print(msg)
    Status(msg)
    if LC.UI then LC.UI:Refresh() end
end
