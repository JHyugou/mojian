# -*- coding: utf-8 -*-
"""墨笺 Agent 评测脚本。

两条路径：

1) 离线体检（默认，不花钱）
   - 校验 Agent 上下文装填是否在预算内、角色工具子集是否正确、导出是否完整；
   - 验证"能跑、没配错"，适合每次改完代码跑一遍。

2) 在线评测（--live，会消耗模型额度）
   - 逐条跑 dataset.json 里的任务，用可机器校验的规则评分：
     是否调用了期望的工具、是否调用了禁止的工具、回答长度是否合理；
   - 结果写进 scripts/eval/reports/ 下的 JSON，便于对比提示词改动前后。

用法：
    python scripts/eval/run_eval.py                # 离线体检
    python scripts/eval/run_eval.py --live --limit 5  # 先跑 5 条试试
    python scripts/eval/run_eval.py --live            # 全量 30 条（会花钱）

依赖：仅 Python 标准库。
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DATASET = ROOT / "dataset.json"
REPORT_DIR = ROOT / "reports"


def api(method, path, base, body=None, timeout=300):
    url = base.rstrip("/") + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if data else {}
    req = urllib.request.Request(url, method=method, data=data, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    if payload.get("code") != 0:
        raise RuntimeError(f"{method} {path} -> {payload.get('code')} {payload.get('message')}")
    return payload.get("data")


class Checker:
    def __init__(self):
        self.total = 0
        self.failed = 0

    def check(self, name, ok, detail=""):
        self.total += 1
        if not ok:
            self.failed += 1
        icon = "[OK]" if ok else "[FAIL]"
        print(f"  {icon} {name}" + (f" — {detail}" if detail and not ok else ""))
        return ok


def pick_project(base, project_id=None):
    projects = api("GET", "/projects", base)
    if not projects:
        raise RuntimeError("没有创作项目：请先在界面里建一本，或导入一本")
    if project_id:
        for project in projects:
            if str(project["id"]) == str(project_id):
                return project
        raise RuntimeError(f"找不到项目 {project_id}")
    # 默认选"章节最多"的项目：空项目能通过的检查太少，体检意义不大
    best, best_count = projects[0], -1
    for project in projects:
        try:
            count = len(api("GET", f"/projects/{project['id']}/chapters", base))
        except Exception:  # noqa: BLE001
            count = 0
        if count > best_count:
            best, best_count = project, count
    return best


def offline(base, project_id, checker):
    project = pick_project(base, project_id)
    pid = project["id"]
    print(f"\n== 离线体检：项目「{project.get('name')}」 ==")

    # 1. 工具子集
    planner_tools = {t["name"] for t in api("GET", "/agent/tools?role=PLANNER", base)}
    writer_tools = {t["name"] for t in api("GET", "/agent/tools?role=WRITER", base)}
    checker.check("策划有 propose_plot_options", "propose_plot_options" in planner_tools)
    checker.check("策划没有 save_chapter_draft", "save_chapter_draft" not in planner_tools)
    checker.check("写作有 save_chapter_draft", "save_chapter_draft" in writer_tools)
    checker.check("写作没有 propose_plot_options", "propose_plot_options" not in writer_tools)

    # 2. 上下文预算
    for role in ("PLANNER", "WRITER"):
        report = api("GET", f"/agent/projects/{pid}/context-report?role={role}", base)
        usable = report.get("usableTokens") or 0
        used = report.get("usedTokens") or 0
        pct = report.get("usagePercent") or 0
        checker.check(f"{role} 上下文未超预算", used <= usable, f"used={used} usable={usable}")
        checker.check(f"{role} 至少装入一段材料", bool(report.get("keptSections")), str(report.get("keptSections")))
        checker.check(f"{role} 用量百分比合理(0~100)", 0 <= pct <= 100, str(pct))

    # 3. 导出完整性：勾选过滤 + 项目级字段
    full = api("GET", f"/projects/{pid}/export", base)
    checker.check("导出格式正确", full.get("format") == "mojian-project/v1")
    checker.check("导出包含文风字段", "style" in (full.get("project") or {}))
    only_chars = api("GET", f"/projects/{pid}/export?sections=characters", base)
    checker.check("导出勾选过滤生效", "settings" not in only_chars and "characters" in only_chars,
                  ",".join(only_chars.keys()))

    # 4. 章节/版本/记忆一致性
    chapters = api("GET", f"/projects/{pid}/chapters", base)
    print(f"  · 章节数：{len(chapters)}")
    if chapters:
        chapter = api("GET", f"/chapters/{chapters[0]['id']}", base)
        checker.check("章节详情有状态", bool(chapter.get("status")))
        versions = api("GET", f"/chapters/{chapter['id']}/versions", base)
        checker.check("版本号不小于版本数（数据一致）",
                      (chapter.get("currentVersionNo") or 0) >= len(versions),
                      f"current={chapter.get('currentVersionNo')} versions={len(versions)}")
        memory = api("GET", f"/chapters/{chapter['id']}/memory", base)
        checker.check("章节记忆接口可用", memory is not None)

    # 5. 写作任务状态接口（P5 新增）
    checker.check("运行轨迹接口可用", isinstance(api("GET", "/agent/usage", base), dict))
    return checker


def evaluate_case(case, data):
    answer = data.get("answer") or ""
    trace = data.get("trace") or []
    tools = [step.get("toolName") for step in trace]
    expect = case.get("expect") or {}
    problems = []

    for tool in expect.get("toolsAny", []):
        if tool not in tools:
            problems.append(f"未调用期望工具 {tool}（实际：{tools or '无'}）")
    for tool in expect.get("forbidTools", []):
        if tool in tools:
            problems.append(f"调用了禁止工具 {tool}")
    if len(answer) < expect.get("minChars", 0):
        problems.append(f"回答过短（{len(answer)} < {expect.get('minChars')}）")
    if expect.get("maxChars") and len(answer) > expect["maxChars"]:
        problems.append(f"回答过长（{len(answer)} > {expect['maxChars']}）")
    return problems, tools


def live(base, project_id, checker, limit):
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    cases = dataset["cases"][:limit] if limit else dataset["cases"]
    project = pick_project(base, project_id)
    pid = project["id"]
    print(f"\n== 在线评测：项目「{project.get('name')}」，{len(cases)} 条用例 ==")
    print("  注意：会真实调用模型并产生费用。\n")

    rows = []
    for index, case in enumerate(cases, 1):
        started = time.time()
        try:
            data = api("POST", "/agent/planner/chat", base, {
                "projectId": pid,
                "conversationId": None,
                "message": case["user"],
                "runId": f"eval-{uuid.uuid4().hex[:10]}",
            })
            problems, tools = evaluate_case(case, data)
        except Exception as exc:  # noqa: BLE001 - 单条失败不应中断整套
            problems, tools = [f"调用失败：{exc}"], []
            data = {}
        elapsed = int((time.time() - started) * 1000)
        passed = not problems
        checker.check(f"[{index:02d}] {case['id']}", passed, "；".join(problems))
        rows.append({
            "id": case["id"], "category": case.get("category"), "passed": passed,
            "problems": problems, "tools": tools, "latencyMs": elapsed,
            "steps": data.get("steps"), "usage": data.get("usage"),
            "answer": (data.get("answer") or "")[:500],
        })
        time.sleep(0.5)

    REPORT_DIR.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = REPORT_DIR / f"live-{stamp}.json"
    report_path.write_text(json.dumps({"projectId": pid, "rows": rows}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n  报告已写入：{report_path}")


def main():
    # Windows 控制台默认 GBK，中文与符号会报编码错误；统一成 UTF-8
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:  # noqa: BLE001 - 老 Python 没有 reconfigure 就算了
        pass
    parser = argparse.ArgumentParser(description="墨笺 Agent 评测")
    parser.add_argument("--base", default="http://localhost:8081/api", help="后端 API 地址")
    parser.add_argument("--project-id", default=None, help="指定项目；默认取第一个")
    parser.add_argument("--live", action="store_true", help="真实调用模型（会花钱）")
    parser.add_argument("--limit", type=int, default=0, help="live 模式只跑前 N 条")
    args = parser.parse_args()

    if not DATASET.exists():
        print(f"找不到评测集：{DATASET}", file=sys.stderr)
        return 2
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    if len(dataset["cases"]) < 30:
        print(f"警告：评测集只有 {len(dataset['cases'])} 条，目标是 30 条", file=sys.stderr)

    checker = Checker()
    try:
        offline(args.base, args.project_id, checker)
        if args.live:
            live(args.base, args.project_id, checker, args.limit)
    except Exception as exc:  # noqa: BLE001 - 顶层只负责给出可读错误
        print(f"\n评测中断：{exc}", file=sys.stderr)
        return 2

    print(f"\n== 结果：{checker.total - checker.failed}/{checker.total} 项通过 ==")
    return 1 if checker.failed else 0


if __name__ == "__main__":
    sys.exit(main())
