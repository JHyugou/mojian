# 墨笺 Docker 部署验证脚本
# 用法：在 mojian 目录执行  powershell -ExecutionPolicy Bypass -File scripts/verify_docker.ps1
$ErrorActionPreference = 'Stop'

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw '未检测到 Docker，请先安装 Docker Desktop 并启动 Docker Engine'
}

Write-Host '== 1. 校验 compose 配置 =='
docker compose -f docker-compose.yml config --quiet

Write-Host '== 2. 构建镜像 =='
docker compose -f docker-compose.yml build

Write-Host '== 3. 启动服务 =='
docker compose -f docker-compose.yml up -d

Write-Host '== 4. 等待后端健康检查 =='
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Seconds 2
    try {
        $resp = Invoke-RestMethod -Uri 'http://localhost:8080/api/health' -TimeoutSec 3
        if ($resp.code -eq 0) {
            $ready = $true
            break
        }
    } catch {
        # 未就绪，继续等待
    }
}
if (-not $ready) {
    docker compose logs backend
    throw '后端健康检查超时'
}

Write-Host '== 5. 前端访问检查 =='
$web = Invoke-WebRequest -Uri 'http://localhost/' -UseBasicParsing -TimeoutSec 5
if ($web.StatusCode -ne 200) {
    throw '前端访问失败'
}

Write-Host '== 6. 端到端冒烟（可选，需要 Python 3）=='
if (Get-Command python -ErrorAction SilentlyContinue) {
    python scripts/smoke_e2e.py
} else {
    Write-Host '未安装 Python，跳过 smoke_e2e.py'
}

Write-Host 'Docker 验证通过 ✅'