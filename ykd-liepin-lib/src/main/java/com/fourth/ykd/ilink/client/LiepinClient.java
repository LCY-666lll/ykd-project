package com.fourth.ykd.ilink.client;

import com.fourth.ykd.ilink.config.LiepinProperties;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import com.microsoft.playwright.options.LoadState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 猎聘网浏览器自动化客户端。
 * 使用 Playwright 实现 Cookie 登录、岗位搜索、简历投递。
 */
@Slf4j
@Component
public class LiepinClient {

    private final LiepinProperties properties;

    private Playwright playwright;
    private Browser browser;
    private BrowserContext context;
    private Page page;
    private volatile boolean loggedIn = false;

    public LiepinClient(LiepinProperties properties) {
        this.properties = properties;
    }

    // ============ 会话管理 ============

    /**
     * 使用 Cookie 登录猎聘。如果已登录则跳过。
     * @return true 登录成功，false Cookie 无效或为空
     */
    public synchronized boolean login() {
        if (loggedIn && page != null && !page.isClosed()) {
            return true;
        }

        String cookie = properties.getCookie();
        if (cookie == null || cookie.isBlank()) {
            log.warn("[Liepin] cookie 为空，无法登录。请在 application-local.properties 配置 liepin.cookie");
            return false;
        }

        try {
            close();

            playwright = Playwright.create();
            browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(properties.isHeadless())
            );

            // 创建浏览器上下文，注入 Cookie
            context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
            );

            // 先访问猎聘首页，再设置 Cookie
            page = context.newPage();
            page.navigate("https://www.liepin.com");
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            // 解析并注入 Cookie
            injectCookies(cookie);

            // 刷新页面使 Cookie 生效
            page.reload();
            page.waitForLoadState(LoadState.DOMCONTENTLOADED);

            // 注入 Cookie 后直接标记为已登录，搜索时再实际验证
            loggedIn = true;
            log.info("[Liepin] Cookie 已注入，等待搜索验证");
            return true;
        } catch (Exception e) {
            log.error("[Liepin] 登录失败: {}", e.getMessage());
            close();
            return false;
        }
    }

    /**
     * 检查当前是否已登录。
     */
    public boolean isLoggedIn() {
        return loggedIn && page != null && !page.isClosed();
    }

    /**
     * 解析 Cookie 字符串并注入到浏览器上下文。
     */
    private void injectCookies(String cookieStr) {
        List<Cookie> cookies = new ArrayList<>();
        for (String pair : cookieStr.split(";")) {
            String trimmed = pair.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String name = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                if (!name.isEmpty()) {
                    cookies.add(new Cookie(name, value)
                            .setDomain(".liepin.com")
                            .setPath("/"));
                }
            }
        }
        context.addCookies(cookies);
        log.debug("[Liepin] 注入 {} 个 Cookie", cookies.size());
    }

    /**
     * 检查登录状态：页面上是否有用户头像或用户名元素。
     */
    private boolean checkLoginStatus() {
        try {
            // 猎聘登录后页面会有用户相关元素
            page.waitForSelector("div.ant-dropdown-trigger.user-nav-icon, " +
                            "a[data-mark='login_user_name'], " +
                            "div.user-nav",
                    new Page.WaitForSelectorOptions().setTimeout(properties.getTimeoutMs()));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ============ 搜索岗位 ============

    /**
     * 搜索岗位。
     *
     * @param keyword    搜索关键词，如 "Java开发"
     * @param city       城市名称，如 "北京"，为空则不限
     * @param salary     薪资范围，如 "20-40k"，为空则不限
     * @param experience 经验要求，如 "3-5年"，为空则不限
     * @return 岗位信息列表
     */
    public List<JobInfo> searchJobs(String keyword, String city, String salary, String experience) {
        if (!login()) {
            return Collections.emptyList();
        }

        try {
            // 构建搜索 URL
            String searchUrl = buildSearchUrl(keyword, city, salary, experience);
            log.info("[Liepin] 搜索岗位: keyword={}, city={}, salary={}, experience={}, url={}", keyword, city, salary, experience, searchUrl);

            page.navigate(searchUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(20000));
            randomDelay();

            // 检查是否被重定向到登录页
            String currentUrl = page.url();
            if (currentUrl.contains("passport.liepin.com") || currentUrl.contains("login")) {
                log.warn("[Liepin] 被重定向到登录页，Cookie 无效: {}", currentUrl);
                loggedIn = false;
                return Collections.emptyList();
            }

            // 打印页面标题用于调试
            log.info("[Liepin] 页面标题: {}, URL: {}", page.title(), currentUrl);

            // 等待岗位列表加载（使用 data-nick 属性或 job-detail-box 类名）
            boolean found = false;
            String[] selectors = {
                    "a[data-nick='job-detail-job-info']",
                    "div.job-detail-box",
                    "div.job-list-box"
            };
            for (String sel : selectors) {
                try {
                    page.waitForSelector(sel, new Page.WaitForSelectorOptions().setTimeout(5000));
                    if (page.locator(sel).count() > 0) {
                        log.info("[Liepin] 匹配到选择器: {}, 数量: {}", sel, page.locator(sel).count());
                        found = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (!found) {
                // 检查是否有"无结果"提示
                String bodyText = page.locator("body").innerText();
                if (bodyText.contains("没有找到") || bodyText.contains("暂无结果") || bodyText.contains("无相关职位")) {
                    log.info("[Liepin] 页面显示无结果");
                } else {
                    log.warn("[Liepin] 未匹配到任何岗位列表选择器，页面内容片段: {}", bodyText.substring(0, Math.min(500, bodyText.length())));
                }
                return Collections.emptyList();
            }

            // 解析岗位列表
            List<JobInfo> jobs = parseJobList();
            log.info("[Liepin] 找到 {} 个岗位，开始检测投递类型...", jobs.size());

            // 逐个检查岗位详情页，区分"可投递"和"仅聊一聊"
            for (int i = 0; i < jobs.size(); i++) {
                JobInfo job = jobs.get(i);
                if (job.url() != null && !job.url().isBlank()) {
                    boolean canApply = checkJobCanApply(job.url());
                    jobs.set(i, new JobInfo(job.title(), job.company(), job.salary(),
                            job.city(), job.experience(), job.url(), canApply));
                    log.info("[Liepin] [{}/{}] {} → {}", i + 1, jobs.size(), job.title(),
                            canApply ? "可投递" : "仅聊一聊");
                }
            }

            return jobs;
        } catch (Exception e) {
            log.error("[Liepin] 搜索失败: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 构建猎聘搜索 URL。同时兼容新版和旧版 URL 参数格式。
     */
    private String buildSearchUrl(String keyword, String city, String salary, String experience) {
        StringBuilder url = new StringBuilder("https://www.liepin.com/zhaopin/?");

        if (keyword != null && !keyword.isBlank()) {
            // 新版用 key，旧版用 keyword，都加上
            url.append("key=").append(encodeParam(keyword));
            url.append("&keyword=").append(encodeParam(keyword));
            url.append("&");
        }

        // 城市代码映射（常见城市）
        if (city != null && !city.isBlank()) {
            String cityCode = getCityCode(city);
            if (cityCode != null) {
                url.append("dq=").append(cityCode);
                url.append("&city=").append(cityCode);
                url.append("&");
            }
        }

        // 薪资筛选
        if (salary != null && !salary.isBlank()) {
            String salaryCode = getSalaryCode(salary);
            if (salaryCode != null) {
                url.append("salary=").append(salaryCode).append("&");
            }
        }

        // 经验筛选
        if (experience != null && !experience.isBlank()) {
            String expCode = getExperienceCode(experience);
            if (expCode != null) {
                url.append("workYearCode=").append(expCode).append("&");
            }
        }

        return url.toString();
    }

    /**
     * 解析岗位列表页面。猎聘使用混淆 CSS 类名，通过 data-nick 属性和语义化类名定位。
     */
    private List<JobInfo> parseJobList() {
        List<JobInfo> jobs = new ArrayList<>();

        // 方案1：通过 data-nick 属性定位岗位链接（最可靠）
        var jobLinks = page.locator("a[data-nick='job-detail-job-info']");
        int linkCount = jobLinks.count();
        log.info("[Liepin] 通过 data-nick 找到 {} 个岗位链接", linkCount);

        if (linkCount > 0) {
            int count = Math.min(linkCount, 15);
            for (int i = 0; i < count; i++) {
                try {
                    var link = jobLinks.nth(i);
                    String jobUrl = link.getAttribute("href");
                    if (jobUrl != null && !jobUrl.startsWith("http")) {
                        jobUrl = "https://www.liepin.com" + jobUrl;
                    }

                    // 从链接的 innerText 获取标题（链接内通常就是岗位标题）
                    String linkText = link.innerText().trim();

                    // 获取链接所在的卡片容器
                    var card = link.locator("xpath=ancestor::div[contains(@class,'job-detail-box') or contains(@class,'_40108')][1]");
                    if (card.count() == 0) card = link.locator("xpath=..");
                    // 从整个卡片文本中提取信息
                    String cardText = card.first().innerText();
                    String[] lines = cardText.split("\n");

                    String title = linkText; // 优先使用链接文本作为标题
                    String company = "";
                    String salary = "";
                    String city = "";
                    String experience = "";

                    // 解析卡片中的其他行
                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty() || line.equals(linkText)) continue;

                        // 薪资特征：包含 k 或 万 或 元，且包含数字
                        if ((line.matches(".*\\d+[kK].*") || line.contains("万") || line.contains("元"))
                                && line.matches(".*\\d.*") && line.length() < 30) {
                            salary = line;
                        }
                        // 城市特征：包含常见城市名
                        else if (line.matches(".*(北京|上海|广州|深圳|杭州|成都|南京|武汉|西安|苏州|天津|重庆|长沙|郑州|厦门|青岛|大连|宁波|合肥|济南|沈阳|昆明|南昌|哈尔滨|长春|石家庄|太原|兰州|南宁|海口|银川|呼和浩特|拉萨|西宁|乌鲁木齐|珠海|佛山|东莞|福州|贵阳).*")
                                && line.length() < 30) {
                            city = line;
                        }
                        // 公司名：通常在标题之后，不含特殊字符
                        else if (company.isEmpty() && line.length() > 1 && line.length() < 30
                                && !line.contains("http") && !line.contains("@")
                                && !line.matches(".*\\d{2,}.*") // 不含连续数字
                                && !line.contains("元") && !line.contains("k") && !line.contains("K")) {
                            company = line;
                        }
                    }

                    // 判断是否可直接投递（卡片上有"投简历"按钮 vs "聊一聊"按钮）
                    boolean canApply = cardText.contains("投简历") || cardText.contains("立即申请") || cardText.contains("投递简历");
                    boolean chatOnly = cardText.contains("聊一聊") && !canApply;

                    if (!title.isEmpty()) {
                        jobs.add(new JobInfo(title, company, salary, city, experience, jobUrl != null ? jobUrl : "", canApply || !chatOnly));
                        log.debug("[Liepin] 岗位: {} {}", title, chatOnly ? "[仅聊一聊]" : "[可投递]");
                    }

                    // 调试：打印第一个卡片的解析结果
                    if (i == 0) {
                        log.info("[Liepin] 第一个卡片解析: title='{}', cardText='{}'",
                                title, cardText.substring(0, Math.min(200, cardText.length())));
                    }
                } catch (Exception e) {
                    log.debug("[Liepin] 解析第 {} 个岗位失败: {}", i, e.getMessage());
                }
            }
            if (!jobs.isEmpty()) return jobs;
        }

        // 方案2：通过 job-detail-box 类名定位
        var detailBoxes = page.locator("div.job-detail-box");
        int boxCount = detailBoxes.count();
        log.info("[Liepin] 通过 job-detail-box 找到 {} 个", boxCount);

        if (boxCount > 0) {
            int count = Math.min(boxCount, 15);
            for (int i = 0; i < count; i++) {
                try {
                    var box = detailBoxes.nth(i);
                    String jobUrl = extractHref(box, "a[href*='/lptjob/'], a[href*='liepin.com']");
                    String cardText = box.innerText();
                    String[] lines = cardText.split("\n");

                    String title = "";
                    String company = "";
                    String salary = "";
                    String city = "";
                    String experience = "";

                    for (String line : lines) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        if (line.matches(".*\\d+[kK].*") || line.contains("万") || line.contains("元")) {
                            salary = line;
                        } else if (line.matches(".*(北京|上海|广州|深圳|杭州|成都|南京|武汉|西安|苏州|天津|重庆|长沙|郑州).*")) {
                            city = line;
                        } else if (line.contains("年") && line.length() < 15 && line.matches(".*\\d.*")) {
                            experience = line;
                        } else if (title.isEmpty() && line.length() > 2 && line.length() < 50) {
                            title = line;
                        } else if (!title.isEmpty() && company.isEmpty() && line.length() > 1 && line.length() < 30) {
                            company = line;
                        }
                    }

                    if (!title.isEmpty()) {
                        boolean canApply = cardText.contains("投简历") || cardText.contains("立即申请") || cardText.contains("投递简历");
                        boolean chatOnly = cardText.contains("聊一聊") && !canApply;
                        jobs.add(new JobInfo(title, company, salary, city, experience, jobUrl != null ? jobUrl : "", canApply || !chatOnly));
                    }
                } catch (Exception e) {
                    log.debug("[Liepin] 解析第 {} 个岗位失败: {}", i, e.getMessage());
                }
            }
        }

        return jobs;
    }

    /**
     * 从元素中提取文本。
     */
    private String extractTextLocator(Object parent, String selector) {
        try {
            Locator locator;
            if (parent instanceof Page p) {
                locator = p.locator(selector);
            } else if (parent instanceof Locator l) {
                locator = l.locator(selector);
            } else {
                return null;
            }
            if (locator.count() > 0) {
                return locator.first().innerText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String extractText(Object parent, String selector) {
        return extractTextLocator(parent, selector);
    }

    /**
     * 从元素中提取链接。
     */
    private String extractHref(Object parent, String selector) {
        try {
            Locator locator;
            if (parent instanceof Page p) {
                locator = p.locator(selector);
            } else if (parent instanceof Locator l) {
                locator = l.locator(selector);
            } else {
                return null;
            }
            if (locator.count() > 0) {
                String href = locator.first().getAttribute("href");
                if (href != null && !href.startsWith("http")) {
                    href = "https://www.liepin.com" + href;
                }
                return href;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ============ 岗位类型检测 ============

    /**
     * 检查岗位是否支持直接投递简历。
     * 访问岗位详情页，查找"投简历"按钮。
     *
     * @param jobUrl 岗位详情页 URL
     * @return true 可直接投递，false 仅支持聊一聊
     */
    private boolean checkJobCanApply(String jobUrl) {
        try {
            Page detailPage = context.newPage();
            try {
                detailPage.navigate(jobUrl);
                detailPage.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(10000));
                randomDelay();

                // 精确查找岗位详情区域的按钮（排除导航栏）
                String[] applySelectors = {
                        "div[class*='job-info'] button:has-text('投简历')",
                        "div[class*='job-detail'] button:has-text('投简历')",
                        "div[class*='job-content'] button:has-text('投简历')",
                        "div[class*='title'] button:has-text('投简历')",
                        "button[data-nick='apply-btn']",
                        "a:has-text('投简历')",
                        "button:has-text('立即申请')",
                        "button:has-text('投递简历')",
                        "button:has-text('马上投递')",
                        "button:has-text('一键投递')"
                };

                for (String sel : applySelectors) {
                    try {
                        var btn = detailPage.locator(sel);
                        if (btn.count() > 0 && btn.first().isVisible()) {
                            return true;
                        }
                    } catch (Exception ignored) {}
                }

                // 检查是否只有"聊一聊"按钮
                String[] chatSelectors = {
                        "button:has-text('聊一聊')",
                        "button:has-text('继续聊')",
                        "a:has-text('聊一聊')",
                        "a:has-text('继续聊')"
                };

                for (String sel : chatSelectors) {
                    try {
                        var btn = detailPage.locator(sel);
                        if (btn.count() > 0 && btn.first().isVisible()) {
                            return false;
                        }
                    } catch (Exception ignored) {}
                }

                // 默认认为可投递
                return true;
            } finally {
                detailPage.close();
            }
        } catch (Exception e) {
            log.warn("[Liepin] 检查岗位类型失败: {}, 默认可投递", e.getMessage());
            return true;
        }
    }

    // ============ 投递简历 ============

    /**
     * 投递单个岗位。
     *
     * @param jobUrl 岗位详情页 URL
     * @return 投递结果
     */
    public ApplyResult applyJob(String jobUrl) {
        if (!login()) {
            return new ApplyResult("", false, "未登录，请检查 Cookie 配置");
        }

        try {
            page.navigate(jobUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(20000));
            randomDelay();

            // 获取岗位标题（新版猎聘用 data-nick 或混淆类名）
            String title = extractText(page,
                    "h1[data-nick='job-title'], " +
                    "div[data-nick='job-title'], " +
                    "div.job-title, h1.job-title, span.job-title");
            if (title == null || title.isBlank()) {
                // 从页面标题提取
                String pageTitle = page.title();
                title = pageTitle.contains("-") ? pageTitle.split("-")[0].trim() : pageTitle;
            }

            // 检查是否已投递或已沟通
            Locator alreadyApplied = page.locator(
                    "button:has-text('已申请'), button:has-text('已投递'), button:has-text('已沟通'), " +
                    "span:has-text('已申请'), div:has-text('已投递简历')");
            if (alreadyApplied.count() > 0) {
                return new ApplyResult(title, true, "已投递过该岗位");
            }

            // 查找正式投递按钮（"投简历"类）
            // 优先在岗位详情区域查找，避免匹配顶部导航栏按钮
            String[] applySelectors = {
                    "div[class*='job-info'] button:has-text('投简历')",
                    "div[class*='job-detail'] button:has-text('投简历')",
                    "div[class*='job-content'] button:has-text('投简历')",
                    "div[class*='title'] button:has-text('投简历')",
                    "button[data-nick='apply-btn']",
                    "div[class*='apply'] button",
                    "button.ant-btn-primary:has-text('申请')",
                    // 以下为全局匹配（兜底）
                    "button:has-text('立即申请')",
                    "button:has-text('投递简历')",
                    "button:has-text('申请职位')",
                    "button:has-text('马上投递')",
                    "button:has-text('一键投递')",
                    "a:has-text('投简历')",
                    "a:has-text('立即申请')"
            };

            Locator applyBtn = null;
            String matchedSelector = null;
            for (String sel : applySelectors) {
                try {
                    Locator btn = page.locator(sel);
                    if (btn.count() > 0 && btn.first().isVisible()) {
                        applyBtn = btn;
                        matchedSelector = sel;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (applyBtn == null) {
                // 没有正式投递按钮，检查是否有"继续聊"（说明已沟通过但未正式投递）
                Locator chatBtn = page.locator("button:has-text('继续聊'), button:has-text('聊一聊'), a:has-text('继续聊'), a:has-text('聊一聊')");
                if (chatBtn.count() > 0) {
                    return new ApplyResult(title, false, "该岗位只能通过聊天沟通，无法直接投递简历");
                }

                // 打印页面内容用于调试
                try {
                    String bodyText = page.locator("body").innerText();
                    log.warn("[Liepin] 未找到投递按钮，页面URL: {}, 页面文本片段: {}", jobUrl, bodyText.substring(0, Math.min(500, bodyText.length())));
                } catch (Exception e) {
                    log.warn("[Liepin] 未找到投递按钮，页面URL: {}", jobUrl);
                }
                return new ApplyResult(title, false, "未找到投递按钮，可能需要手动投递");
            }

            // 点击投递
            log.info("[Liepin] 点击投递按钮: {} (选择器: {})", applyBtn.first().innerText(), matchedSelector);
            applyBtn.first().click();
            randomDelay();

            // 处理可能弹出的确认对话框
            handleApplyDialog();

            // 检查投递结果
            boolean success = checkApplyResult();
            String msg = success ? "投递成功" : "投递失败，请手动检查";

            log.info("[Liepin] {} - {}", title, msg);
            return new ApplyResult(title, success, msg);
        } catch (Exception e) {
            log.error("[Liepin] 投递失败: {}", e.getMessage());
            return new ApplyResult("", false, "投递异常: " + e.getMessage());
        }
    }

    /**
     * 批量投递岗位。
     *
     * @param jobUrls 岗位 URL 列表
     * @return 投递结果列表
     */
    public List<ApplyResult> applyJobs(List<String> jobUrls) {
        List<ApplyResult> results = new ArrayList<>();
        for (String url : jobUrls) {
            results.add(applyJob(url));
            randomDelay(); // 投递间隔，避免被风控
        }
        return results;
    }

    /**
     * 处理投递后可能弹出的对话框（如打招呼语、选择简历等）。
     */
    private void handleApplyDialog() {
        try {
            // 等待弹窗出现
            try {
                page.waitForSelector("div.ant-modal, div[class*='modal'], div[class*='dialog']",
                        new Page.WaitForSelectorOptions().setTimeout(3000));
            } catch (Exception ignored) {}

            // 处理任何模态弹窗
            Locator modal = page.locator("div.ant-modal, div[class*='modal'], div[class*='dialog']");
            if (modal.count() > 0) {
                // 等待弹窗内容加载完成（最多等3秒）
                for (int i = 0; i < 6; i++) {
                    randomDelay();
                    try {
                        String text = modal.first().innerText();
                        if (!text.isBlank()) {
                            log.info("[Liepin] 弹窗内容: {}", text.substring(0, Math.min(200, text.length())));
                            break;
                        }
                    } catch (Exception ignored) {}
                }

                // 检查弹窗是否有"投简历"按钮（有些弹窗是二次确认）
                Locator applyInModal = modal.locator("button:has-text('投简历'), button:has-text('立即投递'), button:has-text('确认投递')");
                if (applyInModal.count() > 0) {
                    log.info("[Liepin] 弹窗中有投递按钮: {}", applyInModal.first().innerText());
                    applyInModal.first().click();
                    randomDelay();
                    return;
                }

                // 查找确认/发送按钮
                Locator confirmBtn = modal.locator("button:has-text('确认'), button:has-text('发送'), button:has-text('确定'), button:has-text('投递'), button.ant-btn-primary");
                if (confirmBtn.count() > 0) {
                    log.info("[Liepin] 找到确认按钮: {}", confirmBtn.first().innerText());
                    confirmBtn.first().click();
                    randomDelay();
                    log.info("[Liepin] 已点击确认按钮");
                    // 等待弹窗关闭
                    try {
                        page.waitForSelector("div.ant-modal:not(:visible), div[class*='modal']:not(:visible)",
                                new Page.WaitForSelectorOptions().setTimeout(5000));
                    } catch (Exception ignored) {}
                    randomDelay();
                } else {
                    log.info("[Liepin] 弹窗中未找到确认按钮");
                }
            } else {
                log.info("[Liepin] 未检测到弹窗");
            }
        } catch (Exception e) {
            log.debug("[Liepin] 处理弹窗异常: {}", e.getMessage());
        }
    }

    /**
     * 处理聊天投递：点击"继续聊"或"聊一聊"后，发送跟进消息。
     */
    private void handleChatApply() {
        try {
            randomDelay();
            // 等待聊天窗口加载
            Locator chatPanel = page.locator("div[class*='chat'], div[class*='im-panel'], div[class*='dialog']");
            if (chatPanel.count() > 0) {
                // 找到输入框
                Locator chatInput = chatPanel.locator("textarea, input[type='text'], div[contenteditable='true']");
                if (chatInput.count() > 0) {
                    chatInput.first().fill("您好，我看到贵司的招聘信息，我对这个岗位很感兴趣，希望有机会进一步沟通。");
                    randomDelay();
                    // 发送消息
                    Locator sendBtn = chatPanel.locator("button:has-text('发送'), button.ant-btn-primary");
                    if (sendBtn.count() > 0) {
                        sendBtn.first().click();
                        randomDelay();
                        log.info("[Liepin] 已发送跟进消息");
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[Liepin] 发送跟进消息异常: {}", e.getMessage());
        }
    }

    /**
     * 检查投递是否成功。
     */
    private boolean checkApplyResult() {
        // 等待页面更新
        randomDelay();
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(5000));
        } catch (Exception ignored) {}
        randomDelay();

        try {
            // 检查是否有成功提示
            Locator successIndicator = page.locator(
                    "button:has-text('已申请'), " +
                            "button:has-text('已投递'), " +
                            "button:has-text('已沟通'), " +
                            "div.ant-message-success, " +
                            "span:has-text('投递成功'), " +
                            "span:has-text('申请成功'), " +
                            "div:has-text('投递成功'), " +
                            "div:has-text('申请成功')");

            if (successIndicator.count() > 0) {
                log.info("[Liepin] 检测到投递成功标志");
                return true;
            }

            // 打印页面文本用于调试
            String bodyText = page.locator("body").innerText();
            log.info("[Liepin] 投递结果检查，页面文本片段: {}", bodyText.substring(0, Math.min(800, bodyText.length())));

            // 检查页面文本中是否包含成功标志
            if (bodyText.contains("投递成功") || bodyText.contains("申请成功") || bodyText.contains("已投递") || bodyText.contains("已申请")) {
                log.info("[Liepin] 页面文本中检测到投递成功标志");
                return true;
            }

            // 精确检查岗位详情区域的按钮（排除导航栏）
            String[] applySelectors = {
                    "div[class*='job-info'] button:has-text('投简历')",
                    "div[class*='job-detail'] button:has-text('投简历')",
                    "div[class*='job-content'] button:has-text('投简历')",
                    "div[class*='title'] button:has-text('投简历')",
                    "button[data-nick='apply-btn']",
                    "a:has-text('投简历')"
            };
            for (String sel : applySelectors) {
                try {
                    Locator btn = page.locator(sel);
                    if (btn.count() > 0 && btn.first().isVisible()) {
                        String btnText = btn.first().innerText().trim();
                        if (btnText.contains("已申请") || btnText.contains("已投递")) {
                            log.info("[Liepin] 按钮文字为 '{}'，投递成功", btnText);
                            return true;
                        }
                        log.info("[Liepin] 岗位详情区域投简历按钮仍存在: '{}'，投递可能未成功", btnText);
                        return false;
                    }
                } catch (Exception ignored) {}
            }

            log.info("[Liepin] 未找到岗位详情区域的投简历按钮，默认投递成功");
            return true;
        } catch (Exception e) {
            log.debug("[Liepin] 检查投递结果异常: {}", e.getMessage());
            return false;
        }
    }

    // ============ 辅助方法 ============

    /**
     * 城市名称转猎聘城市代码。
     */
    private String getCityCode(String city) {
        return switch (city.replaceAll("[市]$", "")) {
            case "北京" -> "010";
            case "上海" -> "020";
            case "广州" -> "050020";
            case "深圳" -> "050090";
            case "杭州" -> "070020";
            case "成都" -> "280020";
            case "南京" -> "060020";
            case "武汉" -> "170020";
            case "西安" -> "270020";
            case "苏州" -> "060080";
            case "天津" -> "030";
            case "重庆" -> "040";
            case "长沙" -> "180020";
            case "郑州" -> "150020";
            case "合肥" -> "080020";
            case "厦门" -> "110040";
            case "青岛" -> "120030";
            case "大连" -> "200030";
            case "珠海" -> "050050";
            case "佛山" -> "050060";
            case "东莞" -> "050070";
            case "宁波" -> "070030";
            case "福州" -> "110020";
            case "济南" -> "120020";
            case "沈阳" -> "200020";
            case "昆明" -> "250020";
            case "贵阳" -> "260020";
            case "南昌" -> "160020";
            case "哈尔滨" -> "220020";
            case "长春" -> "230020";
            case "石家庄" -> "130020";
            case "太原" -> "140020";
            case "兰州" -> "290020";
            case "乌鲁木齐" -> "310020";
            case "南宁" -> "240020";
            case "海口" -> "300020";
            case "银川" -> "330020";
            case "呼和浩特" -> "320020";
            case "拉萨" -> "340020";
            case "西宁" -> "350020";
            default -> null;
        };
    }

    /**
     * 薪资描述转猎聘薪资代码。
     */
    private String getSalaryCode(String salary) {
        String s = salary.toLowerCase().replaceAll("\\s+", "");
        if (s.contains("面议")) return "0";

        // 解析 k/月 格式（如 "20-30k"、"20k以上"）
        // 提取第一个数字，按 12 个月换算成年薪
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s);
            if (m.find()) {
                int monthlyK = Integer.parseInt(m.group(1));
                int yearlyWan = monthlyK * 12 / 10; // k/月 → 万/年

                if (yearlyWan < 5) return "5";      // <5万
                if (yearlyWan < 10) return "5";     // 5-10万
                if (yearlyWan < 15) return "10";    // 10-15万
                if (yearlyWan < 20) return "15";    // 15-20万
                if (yearlyWan < 30) return "20";    // 20-30万
                if (yearlyWan < 50) return "30";    // 30-50万
                if (yearlyWan < 100) return "50";   // 50-100万
                return "100";                        // 100万以上
            }
        } catch (Exception ignored) {}

        // 万/年 格式兜底
        if (s.contains("5") && s.contains("10")) return "5";
        if (s.contains("10") && s.contains("15")) return "10";
        if (s.contains("15") && s.contains("20")) return "15";
        if (s.contains("20") && s.contains("30")) return "20";
        if (s.contains("30") && s.contains("50")) return "30";
        if (s.contains("50") && s.contains("100")) return "50";
        return null;
    }

    /**
     * 经验描述转猎聘经验代码。
     */
    private String getExperienceCode(String exp) {
        String e = exp.replaceAll("\\s+", "");
        if (e.contains("应届") || e.contains("实习")) return "0";
        if (e.contains("1") && e.contains("3")) return "1";  // 1-3年
        if (e.contains("3") && e.contains("5")) return "3";  // 3-5年
        if (e.contains("5") && e.contains("10")) return "5"; // 5-10年
        if (e.contains("10")) return "10";                     // 10年以上
        return null;
    }

    /**
     * URL 参数编码。
     */
    private String encodeParam(String param) {
        try {
            return java.net.URLEncoder.encode(param, "UTF-8");
        } catch (Exception e) {
            return param;
        }
    }

    /**
     * 随机延迟，模拟人类操作。
     */
    private void randomDelay() {
        try {
            long delay = ThreadLocalRandom.current().nextLong(
                    properties.getDelayMinMs(),
                    properties.getDelayMaxMs()
            );
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 关闭浏览器资源。
     */
    public synchronized void close() {
        loggedIn = false;
        if (page != null) {
            try { page.close(); } catch (Exception ignored) {}
            page = null;
        }
        if (context != null) {
            try { context.close(); } catch (Exception ignored) {}
            context = null;
        }
        if (browser != null) {
            try { browser.close(); } catch (Exception ignored) {}
            browser = null;
        }
        if (playwright != null) {
            try { playwright.close(); } catch (Exception ignored) {}
            playwright = null;
        }
    }

    @PreDestroy
    public void shutdown() {
        close();
        log.info("[Liepin] 资源已释放");
    }

    // ============ 数据结构 ============

    /**
     * 岗位信息。
     */
    public record JobInfo(
            String title,
            String company,
            String salary,
            String city,
            String experience,
            String url,
            boolean canApply
    ) {
        public JobInfo {
            // 默认可投递
        }

        public JobInfo(String title, String company, String salary, String city, String experience, String url) {
            this(title, company, salary, city, experience, url, true);
        }

        @Override
        public String toString() {
            return String.format("%s | %s | %s | %s%s", title, company, salary, city, canApply ? "" : " [仅聊一聊]");
        }
    }

    /**
     * 投递结果。
     */
    public record ApplyResult(
            String jobTitle,
            boolean success,
            String message
    ) {}
}