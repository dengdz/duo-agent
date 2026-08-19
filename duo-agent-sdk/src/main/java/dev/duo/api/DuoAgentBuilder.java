package dev.duo.api;

import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmAdapter;
import dev.duo.api.llm.LlmRuntime;
import dev.duo.adapter.deepseek.DeepSeekAdapter;
import dev.duo.core.DuoAgentImpl;
import dev.duo.core.agent.ReactLoopAgent;
import dev.duo.core.llm.SystemPromptImpl;
import dev.duo.core.llm.ToolRegistryImpl;
import dev.duo.core.session.Session;
import dev.duo.model.llm.ToolDefinition;
import dev.duo.model.llm.ToolProviderResult;
import dev.duo.model.llm.ToolSchema;
import dev.duo.model.session.SessionId;
import dev.duo.tool.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Duo Agent 构建器 - 使用 Fluent API 创建 Agent。
 * <p>
 * 这是创建 Agent 的推荐方式，提供了清晰的配置接口和智能默认值。
 * </p>
 * <p>
 * <b>基础示例：</b>
 * <pre>{@code
 * var agent = DuoAgent.builder()
 *     .apiFormat("openai")
 *     .baseUrl("https://api.deepseek.com")
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .contextWindow(128000)
 *     .maxOutputTokens(4096)
 *     .withFileTools()
 *     .build();
 * }</pre>
 * </p>
 * <p>
 * <b>推理模型示例：</b>
 * <pre>{@code
 * var agent = DuoAgent.builder()
 *     .apiFormat("openai")
 *     .baseUrl("https://api.deepseek.com")
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-reasoner")
 *     .contextWindow(64000)
 *     .maxOutputTokens(8000)
 *     .enableReasoning(true)  // 开启推理
 *     .reasoningTimeout(Duration.ofMinutes(5))
 *     .withCodeTools()
 *     .build();
 * }</pre>
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-19
 */
public final class DuoAgentBuilder {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(DuoAgentBuilder.class);

    // LLM 配置
    private String apiFormat;
    private String model;
    private String apiKey;
    private String baseUrl;

    // 上下文和输出配置
    private Integer contextWindow;
    // 默认 null，由模型决定输出长度，避免截断推理模型
    private Integer maxOutputTokens;

    // 推理配置
    private Boolean reasoningEnabled = false;
    private Duration reasoningTimeout = Duration.ofMinutes(5);

    // Agent 配置
    private Duration timeout = Duration.ofSeconds(60);
    private String systemPrompt = "你是一个智能助手，可以使用工具帮助用户完成任务。";

    // 工具和 Hook
    private final List<ToolDefinition> tools = new ArrayList<>();
    private final AgentHooks.Builder hooksBuilder = AgentHooks.builder();

    /**
     * 包内可见构造器，通过 {@link DuoAgent#builder()} 创建。
     */
    DuoAgentBuilder() {
    }

    // ==================== LLM 基础配置 ====================

    /**
     * 设置 API 格式。
     * <p>
     * 目前支持：
     * <ul>
     *   <li>"openai" - OpenAI API 格式（DeepSeek, Ollama, vLLM 等兼容）</li>
     * </ul>
     * <p>
     * 注意：Anthropic 格式暂未实现，请勿使用。
     * </p>
     *
     * @param format API 格式
     * @return this
     * @throws IllegalArgumentException 如果格式不支持
     */
    public DuoAgentBuilder apiFormat(String format) {
        Objects.requireNonNull(format, "apiFormat 不能为 null");
        // 在入口就拒绝不支持的格式，避免到 build() 才失败
        if (!"openai".equals(format)) {
            throw new IllegalArgumentException(
                    "不支持的 API 格式: " + format + "。目前仅支持 'openai'。" +
                    "Anthropic 格式计划在未来版本支持。"
            );
        }
        this.apiFormat = format;
        return this;
    }

    /**
     * 设置模型名称。
     *
     * @param model 模型名称，如 "deepseek-chat", "gpt-4", "claude-3-5-sonnet-20241022"
     * @return this
     */
    public DuoAgentBuilder model(String model) {
        this.model = Objects.requireNonNull(model, "model 不能为 null");
        return this;
    }

    /**
     * 设置 API Key。
     *
     * @param apiKey API 密钥
     * @return this
     */
    public DuoAgentBuilder apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    /**
     * 设置 API 基础 URL。
     * <p>
     * 示例：
     * <ul>
     *   <li>DeepSeek: https://api.deepseek.com</li>
     *   <li>Ollama: http://localhost:11434/v1</li>
     * </ul>
     * </p>
     * <p>
     * 注意：Claude/Anthropic 格式暂未实现，请勿使用。
     * </p>
     *
     * @param baseUrl 基础 URL
     * @return this
     */
    public DuoAgentBuilder baseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
        return this;
    }

    // ==================== 上下文和输出配置 ====================

    /**
     * 设置模型的上下文窗口大小（输入 + 输出的总 token 数）。
     * <p>
     * 示例：
     * <ul>
     *   <li>deepseek-chat: 128000</li>
     *   <li>gpt-4: 128000</li>
     *   <li>claude-3-5-sonnet: 200000</li>
     * </ul>
     * </p>
     *
     * @param tokens 上下文窗口大小
     * @return this
     */
    public DuoAgentBuilder contextWindow(int tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("contextWindow 必须大于 0");
        }
        this.contextWindow = tokens;
        return this;
    }

    /**
     * 设置单次响应的最大输出 token 数。
     * <p>
     * 对应 LLM API 的 max_tokens 参数。
     * </p>
     * <p>
     * 默认不设置，由模型决定输出长度，避免截断推理模型（如 DeepSeek-R1）的长输出。
     * 仅在需要明确限制时才调用此方法。
     * </p>
     *
     * @param tokens 最大输出 token 数
     * @return this
     */
    public DuoAgentBuilder maxOutputTokens(int tokens) {
        if (tokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens 必须大于 0");
        }
        this.maxOutputTokens = tokens;
        return this;
    }

    // ==================== 推理配置 ====================

    /**
     * 启用模型的深度推理能力（如 DeepSeek-R1, OpenAI O1）。
     * <p>
     * 启用后：
     * <ul>
     *   <li>响应可能包含 &lt;think&gt; 推理过程</li>
     *   <li>LLM 调用超时自动切换为 {@link #reasoningTimeout(Duration)}
     *       （默认 5 分钟，普通模式为 60 秒）</li>
     *   <li>需要模型本身支持此特性（如 deepseek-reasoner）</li>
     * </ul>
     * </p>
     *
     * @param enable true 启用推理，false 禁用（默认）
     * @return this
     */
    public DuoAgentBuilder enableReasoning(boolean enable) {
        this.reasoningEnabled = enable;
        return this;
    }

    /**
     * 设置推理模式下的超时时间（仅在 enableReasoning=true 时生效）。
     * <p>
     * 默认 5 分钟。推理模型（DeepSeek-R1 等）思考耗时长，
     * 超过此时间仍未完成的调用将以 TIMEOUT 失败。
     * </p>
     *
     * @param timeout 超时时间
     * @return this
     */
    public DuoAgentBuilder reasoningTimeout(Duration timeout) {
        Objects.requireNonNull(timeout, "reasoningTimeout 不能为 null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("reasoningTimeout 必须大于 0");
        }
        this.reasoningTimeout = timeout;
        return this;
    }

    // ==================== Agent 配置 ====================

    /**
     * 设置超时时间。
     * <p>
     * 默认 60 秒。
     * </p>
     *
     * @param timeout 超时时长
     * @return this
     */
    public DuoAgentBuilder timeout(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout 不能为 null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout 必须大于 0");
        }
        this.timeout = timeout;
        return this;
    }

    /**
     * 设置系统提示词。
     * <p>
     * 默认为通用助手提示词。
     * </p>
     *
     * @param systemPrompt 系统提示词
     * @return this
     */
    public DuoAgentBuilder systemPrompt(String systemPrompt) {
        this.systemPrompt = Objects.requireNonNull(systemPrompt, "systemPrompt 不能为 null");
        return this;
    }

    // ==================== 工具配置 ====================

    /**
     * 添加单个工具。
     *
     * @param tool 工具定义
     * @return this
     */
    public DuoAgentBuilder tool(ToolDefinition tool) {
        this.tools.add(Objects.requireNonNull(tool, "tool 不能为 null"));
        return this;
    }

    /**
     * 添加多个工具。
     *
     * @param tools 工具定义数组
     * @return this
     */
    public DuoAgentBuilder tools(ToolDefinition... tools) {
        for (var tool : tools) {
            tool(tool);
        }
        return this;
    }

    // ==================== 工具预设 ====================

    /**
     * 启用文件操作工具。
     * <p>
     * 包含：file_read、file_write
     * </p>
     *
     * @return this
     */
    public DuoAgentBuilder withFileTools() {
        tools.add(new FileReadTool().getDefinition());
        tools.add(new FileWriteTool().getDefinition());
        return this;
    }

    /**
     * 启用搜索工具。
     * <p>
     * 包含：grep（内容搜索）、glob（文件名匹配）
     * </p>
     *
     * @return this
     */
    public DuoAgentBuilder withSearchTools() {
        tools.add(new GrepTool().getDefinition());
        tools.add(new GlobTool().getDefinition());
        return this;
    }

    /**
     * 启用编辑工具。
     * <p>
     * 包含：edit（精确字符串替换和插入）
     * </p>
     *
     * @return this
     */
    public DuoAgentBuilder withEditTools() {
        tools.add(new EditTool().getDefinition());
        return this;
    }

    /**
     * 启用代码工具集。
     * <p>
     * 包含：bash、file_read、file_write、grep、glob、edit
     * </p>
     * <p>
     * 这是推荐的代码相关任务预设。
     * </p>
     *
     * @return this
     */
    public DuoAgentBuilder withCodeTools() {
        tools.add(new BashTool().getDefinition());
        withFileTools();
        withSearchTools();
        withEditTools();
        return this;
    }

    /**
     * 启用所有内置工具。
     * <p>
     * 包含：bash、file_read、file_write、grep、glob、edit、todo_write
     * </p>
     * <p>
     * 注意：不包含 skill（需要 SkillRegistry）。
     * </p>
     *
     * @return this
     */
    public DuoAgentBuilder withAllBuiltinTools() {
        withCodeTools();
        tools.add(new TodoWriteTool().getDefinition());
        // 注意：SkillTool 需要 SkillRegistry，这里不包含
        return this;
    }

    // ==================== Hook 配置 ====================

    /**
     * 获取 Hook 构建器（高级用户）。
     * <p>
     * 用于配置 PreStepHook、RequestErrorHook 等扩展点。
     * </p>
     *
     * @return Hook 构建器
     */
    public AgentHooks.Builder hooks() {
        return hooksBuilder;
    }

    // ==================== 构建 ====================

    /**
     * 构建 DuoAgent 实例。
     * <p>
     * 在调用此方法前，必须配置：apiFormat, model, apiKey, baseUrl。
     * </p>
     *
     * @return 配置完成的 DuoAgent 实例
     * @throws IllegalStateException 如果配置不完整或无效
     */
    public DuoAgent build() {
        validateConfig();

        // 1. 创建 LLM Runtime
        var llmRuntime = new LlmRuntime();
        var adapter = createAdapter();
        // 使用 apiFormat 作为 provider 名称
        llmRuntime.registerAdapter(apiFormat, adapter);

        // 2. 创建工具注册表（按工具名去重）
        // 使用 last-wins 语义，显式添加的工具覆盖 preset
        var toolMap = new java.util.LinkedHashMap<String, ToolDefinition>();
        for (var tool : tools) {
            var existing = toolMap.put(tool.name(), tool);
            if (existing != null) {
                logger.warn("工具名称冲突 '{}' - 使用最后添加的定义", tool.name());
            }
        }
        var uniqueTools = new java.util.ArrayList<>(toolMap.values());
        
        var toolRegistry = new ToolRegistryImpl();
        uniqueTools.forEach(toolRegistry::register);

        // 3. 创建 System Prompt（使用去重后的工具列表）
        var systemPromptImpl = new SystemPromptImpl(systemPrompt, false);
        systemPromptImpl.tools(assembly ->
                new ToolProviderResult(
                        uniqueTools.stream()
                                .map(t -> new ToolSchema(t.name(), t.description(), t.parameters()))
                                .toList()
                )
        );

        // 4. 创建 Session
        var sessionId = new SessionId("session-" + UUID.randomUUID().toString());
        var session = new Session(sessionId);

        // 5. 创建 Agent Options
        var agentOptions = new AgentOptions(
                apiFormat,           // API 格式
                apiFormat,           // provider（使用 apiFormat）
                model,               // 模型名称
                contextWindow,       // 上下文窗口
                maxOutputTokens,     // 最大输出 token
                reasoningEnabled,    // 是否启用推理
                reasoningTimeout,    // 推理超时
                timeout,             // 普通超时
                hooksBuilder.build() // Hooks
        );

        // 6. 创建底层 Agent
        var agentId = new SessionId("agent-" + UUID.randomUUID().toString());
        var agent = new ReactLoopAgent(
                agentId,
                agentOptions,
                session,
                llmRuntime,
                systemPromptImpl,
                toolRegistry
        );

        // 7. 返回包装后的 DuoAgent
        return new DuoAgentImpl(agent, session);
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 创建 LLM 适配器。
     * <p>
     * HTTP 层请求超时按应用层最大超时（llmTimeout / reasoningTimeout 的较大者）
     * 加 1 分钟余量计算——必须始终大于应用层超时，否则会先于应用层 barrier
     * 掐断 SSE 流式回复。
     * </p>
     */
    private LlmAdapter createAdapter() {
        var appTimeout = reasoningTimeout != null && reasoningTimeout.compareTo(timeout) > 0
                ? reasoningTimeout : timeout;
        var requestTimeout = appTimeout.plusMinutes(1);
        // apiFormat() 已在入口拒绝非 "openai" 值，此处只需单分支
        // 保留 switch 结构便于未来扩展其他格式
        return switch (apiFormat) {
            case "openai" -> new DeepSeekAdapter(apiKey, baseUrl, requestTimeout);
            // 未来支持 Anthropic
            // case "anthropic" -> new AnthropicAdapter(apiKey, baseUrl, requestTimeout);
            default -> throw new IllegalStateException(
                    "内部错误：不支持的 API 格式 " + apiFormat +
                    "（应该在 apiFormat() 调用时被拒绝）");
        };
    }

    /**
     * 验证配置完整性。
     */
    private void validateConfig() {
        if (apiFormat == null || apiFormat.isBlank()) {
            // 只提示支持的格式
            throw new IllegalStateException(
                    "未配置 API 格式。请调用 .apiFormat(\"openai\") 方法。"
            );
        }

        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "未配置模型名称。请调用 .model(\"模型名称\") 方法。"
            );
        }

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "未设置 API Key。请调用 .apiKey(\"your-api-key\") 方法。"
            );
        }

        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "未设置 API Base URL。请调用 .baseUrl(\"https://api.example.com\") 方法。"
            );
        }
    }
}
