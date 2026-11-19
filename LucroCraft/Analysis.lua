--[[
  LucroCraft - Analysis
  Transforma precos + receitas em uma tabela de lucro.

  Ideia central (custo de oportunidade): mesmo que voce tenha farmado o
  material, ele "custa" o que voce receberia vendendo-o na AH. Por isso o
  custo de um craft e calculado com o preco de mercado dos materiais.
  Lucro negativo significa: vender o material cru rende mais que craftar.
]]

local LC = LucroCraft
local floor, min, huge = math.floor, math.min, math.huge

-- Preco para COMPRAR um material: o mais barato entre NPC e AH.
-- Retorna preco, origem ("vendor", "ah" ou "hist").
function LC:BuyPrice(id)
    local vendor = self.db.vendor[id]
    local p = self.db.prices[id]
    local ah = p and p.qty and p.qty > 0 and p.mkt or nil
    if vendor and ah then
        if ah < vendor then return ah, "ah" end
        return vendor, "vendor"
    end
    if vendor then return vendor, "vendor" end
    if ah then return ah, "ah" end
    local hist = self:HistAvg(id)
    if hist then return hist, "hist" end
    return nil
end

-- Preco pelo qual voce consegue VENDER o produto na AH.
function LC:SellPrice(id)
    local p = self.db.prices[id]
    if p and p.qty and p.qty > 0 and p.mkt then
        if self.db.settings.sellMode == "min" then return p.min, "ah" end
        return p.mkt, "ah"
    end
    local hist = self:HistAvg(id)
    if hist then return hist, "hist" end
    return nil
end

-- Custo total dos materiais de 1 execucao da receita (nil se faltar preco)
function LC:RecipeCost(pid, recipe)
    recipe = recipe or self:FindRecipe(pid)
    if not recipe then return nil end
    local cost = 0
    for rid, count in pairs(recipe.r) do
        local price = self:BuyPrice(rid)
        if not price then return nil end
        cost = cost + price * count
    end
    return cost / (recipe.n or 1) -- custo por unidade produzida
end

-- Quantas vezes da para craftar com o que esta nas bolsas
local function CanMake(recipe)
    local n = huge
    for rid, count in pairs(recipe.r) do
        n = min(n, floor((GetItemCount(rid) or 0) / count))
    end
    if n == huge then n = 0 end
    return n
end

local function Analyze(pid, recipe, prof, char)
    local s = LC.db.settings
    local row = {
        id = pid, recipe = recipe, prof = prof, char = char,
        made = recipe.n or 1, reagents = {},
    }

    -- custo dos materiais
    local cost, missing = 0, false
    for rid, count in pairs(recipe.r) do
        local price, src = LC:BuyPrice(rid)
        row.reagents[#row.reagents + 1] = { id = rid, count = count, price = price, src = src }
        if price then cost = cost + price * count else missing = true end
    end
    table.sort(row.reagents, function(a, b) return (a.price or 0) * a.count > (b.price or 0) * b.count end)
    row.cost = not missing and cost or nil
    row.missing = missing

    -- venda
    local sell, sellSrc = LC:SellPrice(pid)
    row.sell, row.sellSrc = sell, sellSrc
    if sell then
        row.revenue = sell * row.made * (1 - s.ahCut)
    end
    if row.revenue and row.cost then
        row.profit = row.revenue - row.cost
        if row.cost > 0 then row.margin = row.profit / row.cost end
    end

    -- concorrencia na AH
    local p = LC.db.prices[pid]
    row.n = p and p.n or nil
    row.qty = p and p.qty or nil
    row.mine = p and p.mine or 0
    row.scanned = p and p.t or nil

    -- tendencia: preco atual vs. media dos ultimos dias
    local hist, days = LC:HistAvg(pid, true)
    if hist and days and days >= 2 and p and p.mkt and p.qty and p.qty > 0 then
        row.trend = p.mkt / hist - 1
    end

    -- vender ao NPC pode render mais que a AH (acontece com itens "inflados" no NPC)
    local vendorSell = select(11, GetItemInfo(pid))
    if vendorSell and vendorSell > 0 then
        row.npc = vendorSell * row.made
    end

    row.canMake = CanMake(recipe)
    return row
end

-- Gera a lista completa, filtrada e ordenada
function LC:BuildResults()
    local s = self.db.settings
    local results, seen = {}, {}
    local myKey = self:CharKey()

    for charKey, c in pairs(self.db.chars) do
        if s.allChars or charKey == myKey then
            local charName = charKey:match("^(.-) %- ") or charKey
            for prof, p in pairs(c.profs) do
                for pid, recipe in pairs(p.recipes) do
                    if not seen[pid] then
                        seen[pid] = true
                        local row = Analyze(pid, recipe, prof, charName)
                        if not (s.hideUnknown and not row.profit) then
                            row.name = self:ItemName(pid)
                            results[#results + 1] = row
                        end
                    end
                end
            end
        end
    end

    local key, desc = s.sortKey, s.sortDesc
    table.sort(results, function(a, b)
        local va, vb = a[key], b[key]
        if va == nil and vb == nil then return (a.name or "") < (b.name or "") end
        if va == nil then return false end -- valores desconhecidos sempre no fim
        if vb == nil then return true end
        if va == vb then return (a.name or "") < (b.name or "") end
        if type(va) == "string" then
            if desc then return va > vb end
            return va < vb
        end
        if desc then return va > vb end
        return va < vb
    end)
    return results
end

-- Lista de itens que o scan direcionado precisa procurar
function LC:TargetItems()
    local s = self.db.settings
    local myKey = self:CharKey()
    local ids = {}
    for charKey, c in pairs(self.db.chars) do
        if s.allChars or charKey == myKey then
            for _, p in pairs(c.profs) do
                for pid, recipe in pairs(p.recipes) do
                    ids[pid] = true
                    for rid in pairs(recipe.r) do
                        -- materiais vendidos por NPC nao precisam ser escaneados
                        if not self.db.vendor[rid] then ids[rid] = true end
                    end
                end
            end
        end
    end
    local list = {}
    for id in pairs(ids) do
        local name = GetItemInfo(id) or self.db.names[id]
        if name then list[#list + 1] = { id = id, name = name } end
    end
    table.sort(list, function(a, b) return a.name < b.name end)
    return list
end
