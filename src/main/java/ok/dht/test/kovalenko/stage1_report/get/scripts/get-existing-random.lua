raw_path = "/v0/entity?id=k"
request = function()
    local r = math.random(1, 100000000-1)
    path = raw_path .. r
    return wrk.format("GET", path)
end
