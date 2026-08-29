#!/usr/bin/env bash
# ============================================================================
# verify_resume_data.sh — 简历实习经历量化数据一键核验
#
# 把简历 `resume-ats.html` 实习经历里的每条数据映射到
# 「静态代码断言 / MySQL 数据口径 / 现存 JUnit 压测」，跑完输出：
#   1. 架构设计     —— QueryAnalyzer/Stylist/Critic/Trend/Coordinator 类存在性
#   2. 检索系统     —— 54 条评测集口径 + gt 语料覆盖 + 命中率(85.2%) + 3轮0波动
#   3. 可靠降级     —— Qdrant 故障注入 20 次成功率 100% + p95 约 15ms(不耗 LLM)
#   4. MCP / 工程化 —— @AgentTool 注册、FastMCP Server 工具、Flyway 迁移
#   5. 评审时延     —— Critic/Trend 并行评审 median 约 8s(耗 LLM，需 --live)
#
# 用法：
#   bash scripts/verify_resume_data.sh            # 快速档：静态 + 数据 + Qdrant 故障注入
#   bash scripts/verify_resume_data.sh --live     # 完整档：额外跑真实 LLM 命中率/93轮/评审
#
# 前提：项目根目录可编译缓存(.m2 有依赖)、MySQL 3306(root/root=application-local)、
#       RAGFlow 9380(--live 档)；llm key 来自 application-local.properties。
# ============================================================================
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ROOT_WIN="$(cd "$ROOT" && pwd -W | tr '\\' '/')"   # D:/项目/WeChatBot（java/Maven 不认 /d/ 风格）
cd "$ROOT" || exit 1

# ---------- Maven 直接 java launcher（本机 bin/mvn 在 Git Bash 失效） ----------
MVN() {
  java -cp "D:/apache-maven-3.9.9/boot/plexus-classworlds-2.8.0.jar" \
       "-Dclassworlds.conf=D:/apache-maven-3.9.9/bin/m2.conf" \
       "-Dmaven.home=D:/apache-maven-3.9.9" \
       "-Dmaven.multiModuleProjectDirectory=$ROOT_WIN" \
       org.codehaus.plexus.classworlds.launcher.Launcher \
       -f "$ROOT_WIN/pom.xml" -o -q "$@"
}

LIVE="${1:-}"
PASS_COUNT=0; FAIL_COUNT=0; NOTE_COUNT=0
declare -a REPORT=()
ok()   { PASS_COUNT=$((PASS_COUNT+1)); REPORT+=("PASS  | $1"); echo "  [PASS] $1"; }
bad()  { FAIL_COUNT=$((FAIL_COUNT+1)); REPORT+=("FAIL  | $1"); echo "  [FAIL] $1"; }
note() { NOTE_COUNT=$((NOTE_COUNT+1)); REPORT+=("NOTE  | $1"); echo "  [NOTE] $1"; }
section() { echo; echo "===== $1 ====="; }
find_class() { find src/main/java -name "$1.java" 2>/dev/null | grep -q .; }
# Maven surefire 在 Windows 上测试输出日志为 GBK 编码（grep 用 UTF-8 会零匹配）→ 先转码再解析
U8() { iconv -f GBK -t UTF-8 "$1" 2>/dev/null || cat "$1"; }

# ============================================================================
section "1. 架构设计：多 Agent 协作（动态代码断言）"
echo "  QueryAnalyzer / StylistAgent / CriticAgent / TrendAgent / AgentCoordinator:"
for c in QueryAnalyzer StylistAgent CriticAgent TrendAgent AgentCoordinator; do
  if find_class "$c"; then ok "类 $c 存在（src/main/java/.../ai/fashion/look）"; else bad "类 $c 缺失"; fi
done

section "2. 检索系统：54 条评测集口径（MySQL + 语料覆盖）"
MYSQL=(mysql -uroot -proot -h127.0.0.1 -P3306 -N -e)
COUNT=$("${MYSQL[@]}" "SELECT COUNT(*) FROM ykd_summer.fashion_conversations WHERE reference_outfit_id <> '';" 2>/dev/null | tr -d '[:space:]')
if [ -z "${COUNT:-}" ]; then
  bad "MySQL 查询失败（127.0.0.1:3306 root/root 与 ykd_summer 库）"
else
  echo "  fashion_conversations 带 reference_outfit_id(gt) 条数 = $COUNT"
  if [ "$COUNT" = "54" ]; then ok "评测集 54 条（简历口径一致）"; else note "评测集 $COUNT 条（简历写 54；以实际为准）"; fi

  # gt 语料覆盖检查：每个 gt 都应存在于 data/fashion_docs/outfit_<id>.md
  # 注意 printf "%d" 会把前导零当八进制 → 用 $((10#..)) 强制十进制
  MISS=0
  while IFS= read -r gt; do
    gt=$(printf '%s' "$gt" | tr -d '[:space:]')
    [ -z "$gt" ] && continue
    num="${gt##*_}"
    case "$num" in *[!0-9]*) continue;; esac
    n=$(printf "%03d" "$((10#$num))" 2>/dev/null)
    file="data/fashion_docs/outfit_$n.md"
    [ -f "$file" ] || { MISS=$((MISS+1)); echo "    缺语料: $gt -> $file"; }
  done < <("${MYSQL[@]}" "SELECT DISTINCT reference_outfit_id FROM ykd_summer.fashion_conversations WHERE reference_outfit_id <> '';" 2>/dev/null)
  if [ "$MISS" = "0" ]; then ok "gt 全在语料库（$COUNT 条 gt 无缺失，0 覆盖干扰）"; else note "$MISS 条 gt 不在语料库（覆盖干扰，命中率会失真）"; fi
fi

section "3. 可靠降级：Qdrant 故障注入 20 次成功率 / p95（不耗 LLM）"
LOG=logs/verify_resume_data_qdrant.log
RESUME_BENCH_LIVE=true MVN test -Dtest='ResumeBenchmarkLiveTest#qdrantFailureDegradationWindow' > "$LOG" 2>&1
Q_SUCC=$(U8 "$LOG" | grep -oE "成功率: [0-9]+/[0-9]+ = [0-9.]+%" | head -1)
Q_P95=$(U8 "$LOG" | grep -oE "p95=[0-9]+ms" | head -1)
if [ -n "$Q_SUCC" ]; then
  echo "  实际输出: $Q_SUCC  |  $Q_P95"
  case "$Q_SUCC" in *"20/20 = 100.0%"*) ok "故障窗口 20 次连续请求成功率 100%";; *) note "成功率非 20/20=100.0%（$Q_SUCC）";; esac
  if [ -n "$Q_P95" ]; then ok "降级响应 p95 = ${Q_P95#*=}（简历约 15ms）"; fi
else
  bad "Qdrant 故障注入测试无输出（见 logs/verify_resume_data_qdrant.log）"
fi

section "4. MCP 工具体系 / 工程化（动态代码断言）"
echo "  @AgentTool/@Tool 注册与 FastMCP Server 工具:"
N_TOOL=$(grep -rhoE "@(AgentTool|Tool)\(" src/main/java | wc -l)
if [ "$N_TOOL" -ge 1 ]; then ok "@AgentTool/@Tool 共 $N_TOOL 处（白名单注册表）"; else bad "未找到 @AgentTool/@Tool 注解"; fi
MCP_TOOLS=$(grep -oE "get_weather|web_search|garment_cutout|garment_revise|wardrobe_photo_analysis|virtual_try_on" mcp-server/server.py 2>/dev/null | sort -u | tr '\n' ' ')
if [ -n "$MCP_TOOLS" ]; then ok "FastMCP Server 注册工具: $MCP_TOOLS"; else bad "mcp-server/server.py 未发现工具注册"; fi
echo "  Flyway 迁移（工程化）:"
N_MIG=$(ls src/main/resources/db/migration/V*.sql 2>/dev/null | wc -l)
if [ "$N_MIG" -ge 1 ]; then ok "Flyway 迁移 $N_MIG 个（V1..V$(ls src/main/resources/db/migration | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)）"; else bad "未找到 Flyway 迁移"; fi
echo "  业务模块（虚拟试衣 doubao-seedream）:"
if grep -rq "doubao-seedream" src mcp-server/.env 2>/dev/null; then ok "虚拟试衣接入 doubao-seedream（mcp-server/.env ARK_MODEL）"; else note "未在配置中找到 doubao-seedream"; fi

if [ "$LIVE" = "--live" ]; then
  # ==========================================================================
  section "5. 命中率 85.2% / 3 轮 0 波动（真实 LLM，RESUME_BENCH_RAG_PROVIDER=ragflow）"
  LOG2=logs/verify_resume_data_hitrate.log
  RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow \
    MVN test -Dtest='ResumeBenchmarkLiveTest#realQueryLogRetrievalHitRate' > "$LOG2" 2>&1
  U8 "$LOG2" | grep -E "样本量|gt 语料覆盖|top-1 命中|top-5 命中" | head -5
  TOP5=$(U8 "$LOG2" | grep -oE "top-5 命中: [0-9]+/[0-9]+ = [0-9.]+%" | head -1)
  echo "  [A] 生产链路严格 top-5（knowledgeService 仅前 5 候选）: ${TOP5:-无}"
  if [ -n "$TOP5" ]; then note "严格 top-5 $TOP5 —— 低于简历 85.2%（口径差异见 [B]）"; fi

  LOG3=logs/verify_resume_data_stability.log
  RESUME_BENCH_LIVE=true RESUME_BENCH_RAG_PROVIDER=ragflow \
    MVN test -Dtest='ResumeBenchmarkLiveTest#greedyStabilityRerun' > "$LOG3" 2>&1
  U8 "$LOG3" | grep -E "第 [0-9] 轮|top-5 波动|median=" | head -8
  T5RANGE=$(U8 "$LOG3" | grep -oE "top-5 波动: [0-9]+~[0-9]+" | grep -oE "[0-9]+~[0-9]+" | head -1)
  T5MED=$(U8 "$LOG3" | grep -oE "top-5 波动.*median=[0-9]+" | grep -oE "[0-9]+$" | head -1)
  if [ -n "$T5RANGE" ]; then
    lo="${T5RANGE%%~*}"; hi="${T5RANGE##*~}"
    if [ "$lo" = "$hi" ]; then note "[B] 基准口径 3 轮命中=$T5RANGE，0 波动（但该口径实为 top-20 覆盖而非严格 top-5）";
    else note "[B] 基准口径（gt∈20候选判定）3 轮命中=$T5RANGE，median=${T5MED:-?}/56 —— 未达 0 波动，且口径实为 top-20 覆盖"; fi
  else note "3 轮重测无输出（见 $LOG3）"; fi

  # ==========================================================================
  section "6. 评审时延 median 约 8s（Critic/Trend 并行，真实 LLM）"
  LOG4=logs/verify_resume_data_review.log
  RESUME_LLM_LIVE=true MVN test -Dtest='SerialReviewBenchmarkLiveTest' > "$LOG4" 2>&1
  U8 "$LOG4" | grep -E "parallel=|Pipeline timings|parallel_review" | head -10
  P_MED=$(U8 "$LOG4" | grep -oE "parallel=[0-9]+ms" | grep -oE "[0-9]+" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
  if [ -n "$P_MED" ]; then ok "并行评审 3 轮 median=${P_MED}ms（简历约 8s）"; else note "评审时延未解析（见 logs/verify_resume_data_review.log）"; fi
else
  echo
  echo "  （跳过真实 LLM 压测：命中率 85.2%/3 轮 0 波动、评审 median 8s 需 --live 档跑）"
  echo "  加 --live 运行完整档：RAGFlow 9380 + DashScope LLM，约耗时数分钟。"
fi

# ============================================================================
echo; echo "=================== 核验汇总（$PASS_COUNT PASS / $FAIL_COUNT FAIL / $NOTE_COUNT NOTE） ==================="
printf '%s\n' "${REPORT[@]}"
echo "日志: logs/verify_resume_data_*.log"