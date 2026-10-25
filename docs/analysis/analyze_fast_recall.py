#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""FT8CN 原快速解码召回率全面分析。

统计口径与 BenchmarkActivity 保持一致：
  key = 报文转大写、按空白切分、取前 3 个 token；<...> 形式的哈希呼号统一成 <...>。

用法：
  python analyze_fast_recall.py <decoded_fast.tsv> <参考txt目录> [bench_fast.txt]
"""

import os
import re
import sys
import statistics
from collections import defaultdict

KEY_TOKENS = 3
GRID_RE = re.compile(r"^[A-Z]{2}\d{2}$")
REPORT_RE = re.compile(r"^R?[+-]\d{2}$")
HASH_RE = re.compile(r"^<.*>$")
REF_RE = re.compile(r"^(\d{6})\s+(-?\d+)\s+([-\d.]+)\s+(\d+)\s+~\s*(.*)$")


def make_key(text):
    if not text:
        return ""
    tokens = text.upper().split()
    out = []
    for tok in tokens[:KEY_TOKENS]:
        if HASH_RE.match(tok):
            tok = "<...>"
        out.append(tok)
    return " ".join(out)


def msg_type(key):
    tokens = key.split()
    if not tokens:
        return "其他"
    if tokens[0] == "CQ":
        return "CQ"
    if "<...>" in tokens:
        return "哈希呼号"
    last = tokens[-1]
    if last in ("73", "RR73"):
        return "73/RR73"
    if REPORT_RE.match(last):
        return "信号报告"
    if GRID_RE.match(last):
        return "网格"
    return "其他"


def parse_ref_file(path):
    """返回参考报文列表：每条含 key/snr/dt/freq/text。"""
    rows = []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.rstrip("\n")
            m = REF_RE.match(line.strip())
            if not m:
                continue
            _, snr, dt, freq, rest = m.groups()
            key = make_key(rest)
            if not key:
                continue
            rows.append({
                "key": key,
                "snr": int(snr),
                "dt": float(dt),
                "freq": int(freq),
                "text": rest.strip(),
            })
    return rows


def parse_decoded(path):
    got = defaultdict(list)
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            wav, text, snr, dt, freq = parts[:5]
            try:
                row = {
                    "key": make_key(text),
                    "text": text,
                    "snr": int(float(snr)),
                    "dt": float(dt),
                    "freq": float(freq),
                }
            except ValueError:
                continue
            if row["key"]:
                got[wav].append(row)
    return got


def ratio(a, b):
    return 100.0 * a / b if b else 0.0


def table(header, rows):
    out = ["| " + " | ".join(header) + " |",
           "|" + "|".join([" --- "] * len(header)) + "|"]
    for r in rows:
        out.append("| " + " | ".join(str(x) for x in r) + " |")
    return "\n".join(out)


def bins_of(value, edges, unit=""):
    for i in range(len(edges) - 1):
        if value < edges[i + 1]:
            return "[%g, %g)%s" % (edges[i], edges[i + 1], unit)
    return ">= %g%s" % (edges[-1], unit)


def main():
    decoded_path, ref_dir = sys.argv[1], sys.argv[2]
    out_path = sys.argv[3] if len(sys.argv) > 3 else None
    if out_path:
        import io
        from contextlib import redirect_stdout
        buf = io.StringIO()
        with redirect_stdout(buf):
            run(decoded_path, ref_dir)
        with open(out_path, "w", encoding="utf-8") as f:
            f.write(buf.getvalue())
        print("written:", out_path)
    else:
        run(decoded_path, ref_dir)


def run(decoded_path, ref_dir):
    got = parse_decoded(decoded_path)

    files = []
    for name in sorted(os.listdir(ref_dir)):
        if name.endswith(".txt"):
            wav = name[:-4] + ".wav"
            refs = parse_ref_file(os.path.join(ref_dir, name))
            if refs:
                files.append((wav, refs))

    # 总体
    total_exp = total_hit = total_extra = total_dup = 0
    per_file = []
    matched_refs = []   # (row, file, hit)
    all_extras = []     # (row, file)

    for wav, refs in files:
        rows = got.get(wav, [])
        got_keys = [r["key"] for r in rows]
        unique_keys = list(dict.fromkeys(got_keys))
        unique_set = set(unique_keys)
        exp_keys = [r["key"] for r in refs]
        exp_set = set(exp_keys)

        hits = sum(1 for k in exp_keys if k in unique_set)
        extras = [k for k in unique_keys if k not in exp_set]
        dup = len(got_keys) - len(unique_keys)

        total_exp += len(refs)
        total_hit += hits
        total_extra += len(extras)
        total_dup += dup

        for r in refs:
            matched_refs.append((r, wav, r["key"] in unique_set))
        for k in extras:
            row = next(x for x in rows if x["key"] == k)
            all_extras.append((row, wav))

        per_file.append({
            "wav": wav,
            "exp": len(refs),
            "hit": hits,
            "recall": ratio(hits, len(refs)),
            "extra": len(extras),
        })

    print("## 总体\n")
    print(table(["项目", "数值"], [
        ["文件数", len(files)],
        ["参考报文", total_exp],
        ["命中", total_hit],
        ["漏解", total_exp - total_hit],
        ["多余（extra）", total_extra],
        ["重复", total_dup],
        ["召回率", "%.1f%%" % ratio(total_hit, total_exp)],
    ]))

    # SNR 分箱
    snr_edges = [-30, -21, -18, -15, -12, -9, -6, -3, 0, 100]
    stat = defaultdict(lambda: [0, 0])
    for r, _, hit in matched_refs:
        b = bins_of(r["snr"], snr_edges, " dB")
        stat[b][0] += 1
        stat[b][1] += int(hit)
    rows = []
    for i in range(len(snr_edges) - 1):
        b = "[%g, %g) dB" % (snr_edges[i], snr_edges[i + 1])
        exp, hit = stat[b]
        rows.append([b, exp, hit, exp - hit, "%.1f%%" % ratio(hit, exp),
                     "%.1f%%" % ratio(exp, total_exp)])
    print("\n## 按参考 SNR 分箱\n")
    print(table(["SNR 区间", "参考条数", "命中", "漏解", "召回率", "占比"], rows))

    # 频率分箱
    freq_edges = [0, 500, 1000, 1500, 2000, 2500, 3000, 4000]
    stat = defaultdict(lambda: [0, 0, 0.0])
    for r, _, hit in matched_refs:
        b = bins_of(r["freq"], freq_edges, " Hz")
        stat[b][0] += 1
        stat[b][1] += int(hit)
        stat[b][2] += r["snr"]
    rows = []
    for i in range(len(freq_edges) - 1):
        b = "[%g, %g) Hz" % (freq_edges[i], freq_edges[i + 1])
        exp, hit, snr_sum = stat[b]
        if exp == 0:
            continue
        rows.append([b, exp, hit, exp - hit, "%.1f%%" % ratio(hit, exp),
                     "%.1f%%" % ratio(exp, total_exp), "%.1f" % (snr_sum / exp)])
    print("\n## 按参考频率分箱\n")
    print(table(["频率区间", "参考条数", "命中", "漏解", "召回率", "占比", "平均 SNR"], rows))
    freqs = [r["freq"] for r, _, _ in matched_refs]
    print("\n参考频率范围：%d ~ %d Hz" % (min(freqs), max(freqs)))

    # 报文类型
    stat = defaultdict(lambda: [0, 0, 0.0])
    for r, _, hit in matched_refs:
        t = msg_type(r["key"])
        stat[t][0] += 1
        stat[t][1] += int(hit)
        stat[t][2] += r["snr"]
    rows = []
    for t in sorted(stat, key=lambda x: -stat[x][0]):
        exp, hit, snr_sum = stat[t]
        rows.append([t, exp, hit, exp - hit, "%.1f%%" % ratio(hit, exp),
                     "%.1f" % (snr_sum / exp)])
    print("\n## 按报文类型\n")
    print(table(["类型", "参考条数", "命中", "漏解", "召回率", "平均 SNR"], rows))

    # 时间偏移
    dt_edges = [0, 0.5, 0.8, 1.1, 1.4, 3.0]
    stat = defaultdict(lambda: [0, 0])
    for r, _, hit in matched_refs:
        b = bins_of(r["dt"], dt_edges, " s")
        stat[b][0] += 1
        stat[b][1] += int(hit)
    rows = []
    for i in range(len(dt_edges) - 1):
        b = "[%g, %g) s" % (dt_edges[i], dt_edges[i + 1])
        exp, hit = stat[b]
        if exp == 0:
            continue
        rows.append([b, exp, hit, exp - hit, "%.1f%%" % ratio(hit, exp),
                     "%.1f%%" % ratio(exp, total_exp)])
    print("\n## 按时隙内时间偏移（参考 dt）\n")
    print(table(["dt 区间", "参考条数", "命中", "漏解", "召回率", "占比"], rows))

    # 每时隙信号数
    load_edges = [0, 11, 21, 31, 41, 100]
    stat = defaultdict(lambda: [0, 0, 0, 0.0])
    for pf, (_, refs) in zip(per_file, files):
        b = bins_of(len(refs), load_edges, " 条")
        stat[b][0] += len(refs)
        stat[b][1] += pf["hit"]
        stat[b][2] += 1
        stat[b][3] += sum(r["snr"] for r in refs)
    rows = []
    for i in range(len(load_edges) - 1):
        b = "[%g, %g) 条" % (load_edges[i], load_edges[i + 1])
        exp, hit, nfile, snr_sum = stat[b]
        if nfile == 0:
            continue
        rows.append([b, nfile, exp, hit, "%.1f%%" % ratio(hit, exp),
                     "%.1f" % (snr_sum / exp)])
    print("\n## 按每时隙信号数量\n")
    print(table(["每时隙参考条数", "时隙数", "参考条数", "命中", "召回率", "平均 SNR"], rows))

    # 同频邻道干扰：最近邻频率距离、±50 Hz 内信号数
    by_wav = defaultdict(list)
    for r, wav, hit in matched_refs:
        by_wav[wav].append((r, hit))

    dist_stat = defaultdict(lambda: [0, 0, 0.0])
    count_stat = defaultdict(lambda: [0, 0, 0.0])
    dist_by_id = {}
    dist_edges = [0, 25, 50, 100, 200, 10000]
    for wav, items in by_wav.items():
        for r, hit in items:
            ds = [abs(r["freq"] - o["freq"]) for o, _ in items if o is not r]
            d = min(ds) if ds else 10000
            dist_by_id[id(r)] = d
            nb = bins_of(d, dist_edges, " Hz")
            dist_stat[nb][0] += 1
            dist_stat[nb][1] += int(hit)
            dist_stat[nb][2] += r["snr"]

            near = sum(1 for x in ds if x <= 50)
            key = str(min(near, 3)) + ("+" if near >= 3 else "")
            count_stat[key][0] += 1
            count_stat[key][1] += int(hit)
            count_stat[key][2] += r["snr"]

    rows = []
    for i in range(len(dist_edges) - 1):
        b = "[%g, %g) Hz" % (dist_edges[i], dist_edges[i + 1])
        exp, hit, snr_sum = dist_stat[b]
        if exp == 0:
            continue
        rows.append([b, exp, hit, exp - hit, "%.1f%%" % ratio(hit, exp),
                     "%.1f" % (snr_sum / exp)])
    print("\n## 按同频最近邻距离（同一时隙内）\n")
    print(table(["最近邻距离", "参考条数", "命中", "漏解", "召回率", "平均 SNR"], rows))

    rows = []
    for key in ["0", "1", "2", "3+"]:
        exp, hit, snr_sum = count_stat[key]
        if exp == 0:
            continue
        rows.append([key, exp, hit, exp - hit, "%.1f%%" % ratio(hit, exp),
                     "%.1f" % (snr_sum / exp)])
    print("\n## 按 ±50 Hz 内其他信号数\n")
    print(table(["邻道信号数", "参考条数", "命中", "漏解", "召回率", "平均 SNR"], rows))

    # 干净信号（最近邻 >= 50 Hz，即不重叠）vs 重叠信号
    clean = [(r, hit) for r, _, hit in matched_refs if dist_by_id.get(id(r), 10000) >= 50]
    dirty = [(r, hit) for r, _, hit in matched_refs if dist_by_id.get(id(r), 10000) < 50]
    clean_hit = sum(1 for _, h in clean if h)
    dirty_hit = sum(1 for _, h in dirty if h)
    print("\n## 干净信号与重叠信号\n")
    print(table(["类别", "参考条数", "命中", "漏解", "召回率", "占漏解比例"], [
        ["最近邻 >= 50 Hz", len(clean), clean_hit, len(clean) - clean_hit,
         "%.1f%%" % ratio(clean_hit, len(clean)),
         "%.1f%%" % ratio(len(clean) - clean_hit, total_exp - total_hit)],
        ["最近邻 < 50 Hz", len(dirty), dirty_hit, len(dirty) - dirty_hit,
         "%.1f%%" % ratio(dirty_hit, len(dirty)),
         "%.1f%%" % ratio(len(dirty) - dirty_hit, total_exp - total_hit)],
    ]))

    # 干净信号内的 SNR 敏感度
    stat = defaultdict(lambda: [0, 0])
    for r, hit in clean:
        b = bins_of(r["snr"], snr_edges, " dB")
        stat[b][0] += 1
        stat[b][1] += int(hit)
    rows = []
    for i in range(len(snr_edges) - 1):
        b = "[%g, %g) dB" % (snr_edges[i], snr_edges[i + 1])
        exp, hit = stat[b]
        if exp == 0:
            continue
        rows.append([b, exp, hit, exp - hit, "%.1f%%" % ratio(hit, exp)])
    print("\n## 按 SNR 分箱（仅最近邻 >= 50 Hz 的干净信号）\n")
    print(table(["SNR 区间", "参考条数", "命中", "漏解", "召回率"], rows))

    # 每文件分布
    recalls = sorted(pf["recall"] for pf in per_file)
    q = lambda p: recalls[min(len(recalls) - 1, int(round(p * (len(recalls) - 1))))]
    print("\n## 每文件召回分布\n")
    print(table(["统计量", "召回率"], [
        ["最低", "%.1f%%" % recalls[0]],
        ["P10", "%.1f%%" % q(0.10)],
        ["P25", "%.1f%%" % q(0.25)],
        ["中位数", "%.1f%%" % q(0.50)],
        ["P75", "%.1f%%" % q(0.75)],
        ["P90", "%.1f%%" % q(0.90)],
        ["最高", "%.1f%%" % recalls[-1]],
        ["标准差", "%.1f 个百分点" % statistics.pstdev(recalls)],
    ]))
    worst = sorted(per_file, key=lambda x: (x["recall"], -x["exp"]))[:5]
    best = sorted(per_file, key=lambda x: (-x["recall"], -x["exp"]))[:5]
    print("\n最差 5 个时隙：\n")
    print(table(["文件", "参考", "命中", "召回率", "多余"],
                [[p["wav"], p["exp"], p["hit"], "%.1f%%" % p["recall"], p["extra"]]
                 for p in worst]))
    print("\n最好 5 个时隙：\n")
    print(table(["文件", "参考", "命中", "召回率", "多余"],
                [[p["wav"], p["exp"], p["hit"], "%.1f%%" % p["recall"], p["extra"]]
                 for p in best]))

    # extra 分析
    print("\n## 多余消息（extra）分析\n")
    print(table(["项目", "数值"], [
        ["多余总数", total_extra],
        ["占参考报文比例", "%.1f%%" % ratio(total_extra, total_exp)],
        ["平均 SNR", "%.1f dB" % (sum(r["snr"] for r, _ in all_extras) / len(all_extras) if all_extras else 0)],
        ["平均频率", "%.0f Hz" % (sum(r["freq"] for r, _ in all_extras) / len(all_extras) if all_extras else 0)],
    ]))
    tstat = defaultdict(int)
    for r, _ in all_extras:
        tstat[msg_type(r["key"])] += 1
    print("\n按类型：\n")
    print(table(["类型", "条数", "占比"],
                [[t, n, "%.1f%%" % ratio(n, total_extra)]
                 for t, n in sorted(tstat.items(), key=lambda x: -x[1])]))
    estat = defaultdict(lambda: [0, 0])
    for r, _ in all_extras:
        b = bins_of(r["snr"], snr_edges, " dB")
        estat[b][0] += 1
        estat[b][1] += 1
    rows = []
    for i in range(len(snr_edges) - 1):
        b = "[%g, %g) dB" % (snr_edges[i], snr_edges[i + 1])
        n = estat[b][0]
        if n:
            rows.append([b, n, "%.1f%%" % ratio(n, total_extra)])
    print("\n按 SNR：\n")
    print(table(["SNR 区间", "条数", "占比"], rows))

    # 弱信号关键结论
    weak = [r for r, _, _ in matched_refs if r["snr"] < -18]
    weak_hit = sum(1 for r, _, hit in matched_refs if r["snr"] < -18 and hit)
    print("\n## 漏解样本（按 SNR 从高到低取 10 条）\n")
    missed = [(r, wav) for r, wav, hit in matched_refs if not hit]
    missed.sort(key=lambda x: -x[0]["snr"])
    print(table(["文件", "报文", "SNR", "频率", "dt"],
                [[wav, r["key"], r["snr"], r["freq"], "%.1f" % r["dt"]]
                 for r, wav in missed[:10]]))

    print("\n## 关键结论数据\n")
    print("- SNR < -18 dB：%d 条，命中 %d 条，召回 %.1f%%"
          % (len(weak), weak_hit, ratio(weak_hit, len(weak))))
    strong = [r for r, _, _ in matched_refs if r["snr"] >= -12]
    strong_hit = sum(1 for r, _, hit in matched_refs if r["snr"] >= -12 and hit)
    print("- SNR >= -12 dB：%d 条，命中 %d 条，召回 %.1f%%"
          % (len(strong), strong_hit, ratio(strong_hit, len(strong))))
    print("- 参考频率范围：%d ~ %d Hz" % (min(freqs), max(freqs)))


if __name__ == "__main__":
    main()
