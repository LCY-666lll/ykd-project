package com.fourth.ykd.ilink.tool;

import com.fourth.ykd.ilink.client.LiepinClient;
import com.fourth.ykd.ilink.client.LiepinClient.ApplyResult;
import com.fourth.ykd.ilink.client.LiepinClient.JobInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 猎聘简历投递工具，提供给大模型调用。
 * 支持按关键词、城市、薪资搜索岗位，并自动投递简历。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiepinApplyTool {

    private final LiepinClient liepinClient;

    /** 缓存最近一次搜索结果，支持用序号投递 */
    private volatile List<JobInfo> lastSearchResults = List.of();

    @Tool(name = "search_liepin_jobs", description = """
            在猎聘网上搜索岗位信息。
            适用场景：用户想找工作、搜索职位、查看招聘信息、投递简历前的岗位搜索。
            返回匹配的岗位列表（包含序号、标题、公司、薪资、城市），供用户选择要投递的岗位。
            用户选择序号后，调用 apply_liepin_jobs 投递。
            """)
    public String searchJobs(
            @ToolParam(description = "搜索关键词，如'Java开发'、'产品经理'、'数据分析'", required = true) String keyword,
            @ToolParam(description = "期望城市，如'北京'、'上海'、'深圳'，不填则不限城市", required = false) String city,
            @ToolParam(description = "期望薪资，如'20-30k'、'30-50k'、'面议'，不填则不限薪资", required = false) String salary,
            @ToolParam(description = "经验要求，如'应届'、'1-3年'、'3-5年'、'5-10年'，不填则不限经验", required = false) String experience
    ) {
        log.info("[AI][TOOL][LIEPIN][SEARCH] keyword={}, city={}, salary={}, experience={}", keyword, city, salary, experience);

        if (keyword == null || keyword.isBlank()) {
            return "请告诉我想找什么岗位，例如：Java开发、产品经理、数据分析";
        }

        try {
            List<JobInfo> jobs = liepinClient.searchJobs(
                    keyword,
                    city != null ? city : "",
                    salary != null ? salary : "",
                    experience != null ? experience : ""
            );

            if (jobs.isEmpty()) {
                lastSearchResults = List.of();
                return "未找到匹配的岗位，建议：\n" +
                        "1. 换个关键词试试\n" +
                        "2. 扩大搜索范围（去掉城市或薪资限制）\n" +
                        "3. 检查猎聘 Cookie 是否有效";
            }

            // 缓存搜索结果
            lastSearchResults = new ArrayList<>(jobs);

            // 分组：可投递 vs 仅聊一聊
            List<JobInfo> applyable = jobs.stream().filter(JobInfo::canApply).toList();
            List<JobInfo> chatOnly = jobs.stream().filter(j -> !j.canApply()).toList();

            StringBuilder sb = new StringBuilder();
            sb.append("找到 ").append(jobs.size()).append(" 个岗位");
            if (!chatOnly.isEmpty()) {
                sb.append("（").append(applyable.size()).append("个可投递，").append(chatOnly.size()).append("个仅支持聊一聊）");
            }
            sb.append("：\n\n");

            // 可直接投递的岗位
            if (!applyable.isEmpty()) {
                sb.append("📌 【可直接投递】回复序号自动投递：\n");
                for (int i = 0; i < jobs.size(); i++) {
                    JobInfo job = jobs.get(i);
                    if (!job.canApply()) continue;
                    sb.append(String.format("  %d. %s | %s | %s", i + 1, job.title(), job.salary(), job.city()));
                    if (job.experience() != null && !job.experience().isBlank()) {
                        sb.append(" | ").append(job.experience());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            // 仅聊一聊的岗位（附链接）
            if (!chatOnly.isEmpty()) {
                sb.append("💬 【仅支持聊一聊】回复序号获取HR对话链接：\n");
                for (int i = 0; i < jobs.size(); i++) {
                    JobInfo job = jobs.get(i);
                    if (job.canApply()) continue;
                    sb.append(String.format("  %d. %s | %s | %s", i + 1, job.title(), job.salary(), job.city()));
                    if (job.experience() != null && !job.experience().isBlank()) {
                        sb.append(" | ").append(job.experience());
                    }
                    sb.append("\n");
                }
                sb.append("\n");
            }

            sb.append("回复序号即可，如\"投递1、3\"或\"全部投递\"。");

            log.info("[AI][TOOL][LIEPIN][SEARCH][SUCCESS] found {} jobs", jobs.size());
            return sb.toString();
        } catch (Exception e) {
            log.error("[AI][TOOL][LIEPIN][SEARCH][FAILED] reason={}", e.getMessage());
            return "搜索岗位失败: " + e.getMessage();
        }
    }

    @Tool(name = "apply_liepin_jobs", description = """
            在猎聘网上投递简历。
            适用场景：用户确认要投递某些岗位后，传入岗位序号或链接进行批量投递。
            支持两种方式：
            1. 传入序号（如"1,3,5"或"全部"），从最近一次搜索结果中查找对应岗位投递
            2. 传入完整的猎聘岗位链接
            可投递的岗位会自动投递简历；仅支持"聊一聊"的岗位会返回HR对话链接。
            """)
    public String applyJobs(
            @ToolParam(description = "要投递的岗位序号（如'1,3,5'或'全部'）或岗位链接，多个用逗号分隔", required = true) String jobUrlsOrIndices
    ) {
        log.info("[AI][TOOL][LIEPIN][APPLY] input={}", jobUrlsOrIndices);

        if (jobUrlsOrIndices == null || jobUrlsOrIndices.isBlank()) {
            return "请提供要投递的岗位序号或链接";
        }

        try {
            // 分离：可投递 vs 仅聊一聊
            List<String> applyUrls = new ArrayList<>();
            List<JobInfo> chatOnlyJobs = new ArrayList<>();
            resolveUrlsByType(jobUrlsOrIndices, applyUrls, chatOnlyJobs);

            // 无可投递且无聊一聊
            if (applyUrls.isEmpty() && chatOnlyJobs.isEmpty()) {
                if (lastSearchResults.isEmpty()) {
                    return "没有找到可投递的岗位。请先搜索岗位，再选择序号投递。";
                }
                return "未识别到有效的岗位序号或链接。请回复序号如\"投递1、3\"或\"全部投递\"。";
            }

            StringBuilder sb = new StringBuilder();

            // 投递可投递的岗位
            if (!applyUrls.isEmpty()) {
                List<ApplyResult> results = liepinClient.applyJobs(applyUrls);
                long successCount = results.stream().filter(ApplyResult::success).count();
                long failCount = results.size() - successCount;

                sb.append("📋 投递结果：\n");
                for (ApplyResult result : results) {
                    sb.append(String.format("  %s %s - %s\n",
                            result.success() ? "✅" : "❌",
                            result.jobTitle(),
                            result.message()));
                }
                sb.append(String.format("  总计: %d 成功, %d 失败\n", successCount, failCount));
            }

            // 仅聊一聊的岗位 → 返回链接
            if (!chatOnlyJobs.isEmpty()) {
                if (!applyUrls.isEmpty()) sb.append("\n");
                sb.append("💬 以下岗位仅支持\"聊一聊\"，点击链接可直接与HR沟通：\n\n");
                for (JobInfo job : chatOnlyJobs) {
                    sb.append(String.format("  🔗 %s | %s | %s\n     %s\n\n",
                            job.title(), job.salary(), job.city(), job.url()));
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("[AI][TOOL][LIEPIN][APPLY][FAILED] reason={}", e.getMessage());
            return "投递失败: " + e.getMessage();
        }
    }

    /**
     * 将用户输入解析为两组：可投递的 URL 列表 + 仅聊一聊的 JobInfo 列表。
     */
    private void resolveUrlsByType(String input, List<String> applyUrls, List<JobInfo> chatOnlyJobs) {
        String trimmed = input.trim();

        if (trimmed.contains("全部")) {
            for (JobInfo job : lastSearchResults) {
                if (job.url() == null || job.url().isBlank()) continue;
                if (job.canApply()) {
                    applyUrls.add(job.url());
                } else {
                    chatOnlyJobs.add(job);
                }
            }
            return;
        }

        // 解析序号（支持 "1,3,5"、"1、3、5"、"1 3 5" 等格式）
        String[] parts = trimmed.replaceAll("[、，\\s]+", ",").split(",");
        boolean allNumeric = true;
        for (String part : parts) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            try {
                int index = Integer.parseInt(p);
                if (index >= 1 && index <= lastSearchResults.size()) {
                    JobInfo job = lastSearchResults.get(index - 1);
                    if (job.url() != null && !job.url().isBlank()) {
                        if (job.canApply()) {
                            applyUrls.add(job.url());
                        } else {
                            chatOnlyJobs.add(job);
                        }
                    }
                } else {
                    log.warn("[Liepin] 序号 {} 超出范围 (1-{})", index, lastSearchResults.size());
                }
            } catch (NumberFormatException e) {
                allNumeric = false;
                break;
            }
        }

        if (allNumeric && (!applyUrls.isEmpty() || !chatOnlyJobs.isEmpty())) {
            return;
        }

        // 非序号，当作 URL 处理（默认可投递）
        applyUrls.clear();
        chatOnlyJobs.clear();
        for (String part : input.split(",")) {
            String p = part.trim();
            if (p.startsWith("http") && p.contains("liepin.com")) {
                applyUrls.add(p);
            }
        }
    }
}