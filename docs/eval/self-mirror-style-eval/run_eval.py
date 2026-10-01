#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
批判镜反馈风格对照评测 —— 生成阶段
对同一批评分题本，分别用两套系统提示（MI 式反讨好 / 对抗式）调用同一个模型，
保证唯一变量是提示风格。兼容任意 OpenAI 兼容端点：
  - Ollama:            --base-url http://localhost:11434/v1
  - LM Studio:         --base-url http://localhost:1234/v1
  - llama.cpp server:  --base-url http://localhost:8080/v1
  - 云端兼容接口:      --base-url https://xxx/v1
没有可用端点时用 --dry-run 生成请求清单（不会伪造任何模型输出）。

示例：
  python3 run_eval.py --model gemma3:4b --base-url http://localhost:11434/v1
  python3 run_eval.py --dry-run
仅依赖 Python 标准库。
"""
import argparse, json, os, sys, time, urllib.request, urllib.error, datetime, pathlib

ROOT = pathlib.Path(__file__).resolve().parent
ARM_FILES = {
    "mi_non_sycophantic": ROOT / "prompts" / "mi_non_sycophantic.md",
    "confrontational": ROOT / "prompts" / "confrontational.md",
}


def build_user_message(case):
    # 被测模型只能看到基准与转写，看不到 expected / 评分意图，保证贴近真实使用。
    return (
        "以下是用户设定的理想人格行为基准：\n"
        f"{case['baseline']}\n\n"
        "以下是该用户一段真实语音的转写文本：\n"
        f"“{case['transcript']}”\n\n"
        "请依据你的角色要求，对这段表达给出你的分析。"
    )


def chat_once(base_url, api_key, model, system, user, temperature, timeout):
    payload = {
        "model": model,
        "temperature": temperature,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }
    req = urllib.request.Request(
        base_url.rstrip("/") + "/chat/completions",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {api_key}"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        data = json.loads(resp.read().decode("utf-8"))
    return data["choices"][0]["message"]["content"]


def safe_name(s):
    return "".join(c if c.isalnum() or c in ".-_" else "_" for c in str(s))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--cases", default=str(ROOT / "cases.json"))
    ap.add_argument("--base-url", default="http://localhost:11434/v1")
    ap.add_argument("--api-key", default="ollama")
    ap.add_argument("--model", default="gemma3:4b", help="被测端侧模型，如 gemma3:4b / qwen2.5:3b")
    ap.add_argument("--temperature", type=float, default=0.3)
    ap.add_argument("--timeout", type=int, default=180)
    ap.add_argument("--arms", default="mi_non_sycophantic,confrontational")
    ap.add_argument("--outdir", default=str(ROOT / "results"))
    ap.add_argument("--dry-run", action="store_true", help="不调用模型，只导出请求清单")
    args = ap.parse_args()

    cases_doc = json.load(open(args.cases, encoding="utf-8"))
    cases = cases_doc["cases"]
    arms = [a.strip() for a in args.arms.split(",") if a.strip()]
    prompts = {a: ARM_FILES[a].read_text(encoding="utf-8") for a in arms}

    outdir = pathlib.Path(args.outdir)
    outdir.mkdir(parents=True, exist_ok=True)
    ts = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    tag = "dryrun" if args.dry_run else safe_name(args.model)

    records = []
    requests_preview = []
    for case in cases:
        user_msg = build_user_message(case)
        for arm in arms:
            requests_preview.append({"case_id": case["id"], "arm": arm,
                                     "system": prompts[arm], "user": user_msg})
            if args.dry_run:
                continue
            t0 = time.time()
            try:
                out = chat_once(args.base_url, args.api_key, args.model,
                                prompts[arm], user_msg, args.temperature, args.timeout)
                rec = {"case_id": case["id"], "title": case["title"], "arm": arm,
                       "ok": True, "latency_sec": round(time.time() - t0, 1),
                       "response": out, "error": None}
                print(f"[ok] {case['id']} / {arm}  ({rec['latency_sec']}s)")
            except urllib.error.URLError as e:
                rec = {"case_id": case["id"], "title": case["title"], "arm": arm,
                       "ok": False, "latency_sec": None, "response": None,
                       "error": f"连接失败：{e}。请确认端点已启动，例如 Ollama: ollama serve 并 ollama pull {args.model}"}
                print(f"[fail] {case['id']} / {arm}: {rec['error']}", file=sys.stderr)
            except Exception as e:
                rec = {"case_id": case["id"], "title": case["title"], "arm": arm,
                       "ok": False, "latency_sec": None, "response": None, "error": str(e)}
                print(f"[fail] {case['id']} / {arm}: {e}", file=sys.stderr)
            records.append(rec)

    bundle = {
        "generated_at": ts,
        "dry_run": args.dry_run,
        "model": None if args.dry_run else args.model,
        "base_url": None if args.dry_run else args.base_url,
        "temperature": args.temperature,
        "cases_version": cases_doc["meta"]["version"],
        "records": records,
    }
    raw_path = outdir / f"raw_{tag}_{ts}.json"
    json.dump(bundle, open(raw_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    if args.dry_run:
        json.dump(requests_preview, open(outdir / f"requests_preview_{ts}.json", "w", encoding="utf-8"),
                  ensure_ascii=False, indent=2)
        print("\n[dry-run] 未调用任何模型、未生成任何回答。")
        print(f"请求清单：{outdir / f'requests_preview_{ts}.json'}")
        print("启动本地端点后去掉 --dry-run 即可真实生成，例如：")
        print(f"  ollama pull {args.model} && ollama serve")
        print(f"  python3 run_eval.py --model {args.model} --base-url http://localhost:11434/v1")
        return

    # 可读 Markdown
    md = [f"# 生成结果（model={args.model}, temperature={args.temperature}, time={ts}）", ""]
    for case in cases:
        md.append(f"## {case['id']} {case['title']}")
        for arm in arms:
            rec = next((r for r in records if r["case_id"] == case["id"] and r["arm"] == arm), None)
            md.append(f"### arm={arm}")
            md.append(rec["response"] if rec and rec["ok"] else f"[生成失败] {rec['error'] if rec else '无记录'}")
            md.append("")
    md_path = outdir / f"raw_{tag}_{ts}.md"
    md_path.write_text("\n".join(md), encoding="utf-8")

    ok_n = sum(1 for r in records if r["ok"])
    print(f"\n完成：{ok_n}/{len(records)} 条成功。")
    print(f"原始结果：{raw_path}")
    print(f"可读版本：{md_path}")
    print("下一步：python3 score.py --raw " + str(raw_path) + " --judge-model <裁判模型>")


if __name__ == "__main__":
    main()
