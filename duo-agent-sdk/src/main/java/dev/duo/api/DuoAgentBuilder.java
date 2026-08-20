package dev.duo.api;

import dev.duo.api.agent.AgentHooks;
import dev.duo.api.agent.AgentOptions;
import dev.duo.api.llm.LlmRuntime;
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
 * 模型配置（凭证、端点、上下文窗口等）由 {@link DuoModel} 承担并在
 * Agent 间复用，Builder 只负责 Agent 专属配置（系统提示词、超时、工具、Hook）。
 * </p>
 * <p>
 * <b>基础示例：</b>
 * <pre>{@code
 * DuoModel model = DeepSeekModel.builder()
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-chat")
 *     .contextWindow(128000)
 *     .build();
 *
 * DuoAgent agent = DuoAgent.builder()
 *     .model(model)
 *     .withCodeTools()
 *     .build();
 * }</pre>
 * </p>
 * <p>
 * <b>推理模型示例：</b>
 * <pre>{@code
 * DuoModel reasoner = DeepSeekModel.builder()
 *     .apiKey(System.getenv("DEEPSEEK_API_KEY"))
 *     .model("deepseek-reasoner")
 *     .contextWindow(64000)
 *     .enableReasoning(true)
 *     .reasoningTimeout(Duration.ofMinutes(5))
 *     .build();
 *
 * DuoAgent agent = DuoAgent.builder()
 *     .model(reasoner)
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

    /** systemPrompt 均未设置时的兜底文案（优先级：Agent 显式 > Model > 本默认）。 */
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个智能助手，可以使用工具帮助用户完成任务。";

    // 模型配置（必填）
    private DuoModel model;

    // Agent 配置
    private Duration timeout = Duration.ofSeconds(60);
    /** null 表示未显式设置，build() 时按优先级解析（见 DEFAULT_SYSTEM_PROMPT 注释）。 */
    private String systemPrompt;

    // 工具和 Hook
    private final List<ToolDefinition> tools = new ArrayList<>();
    private final AgentHooks.Builder hooksBuilder = AgentHooks.builder();

    /**
     * 包内可见构造器，通过 {@link DuoAgent#builder()} 创建。
     */
    DuoAgentBuilder() {
    }

    // ==================== 模型配置 ====================

    /**
     * 设置模型（必填）。
     * <p>
     * 同一 Model 实例可传给多个 Agent，共享模型配置；每个 Agent 经
     * {@link DuoModel#createAdapter(Duration)} 获得独立的适配器实例。
     * </p>
     *
     * @param model 模型实例
     * @return this
     */
    public DuoAgentBuilder model(DuoModel model) {
        this.model = Objects.requireNonNull(model, "model 不能为 null");
        return this;
    }

    // ==================== Agent 配置 ====================

    /**
     * 设置 LLM 调用超时时间。
     * <p>
     * 默认 60 秒。启用推理的 Model 使用其自身的 reasoningTimeout（默认 5 分钟）。
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
     * 设置系统提示词（可选）。
     * <p>
     * <b>优先级：Agent 显式 systemPrompt &gt; Model systemPrompt &gt; 内置默认。</b>
     * 本 Builder 不设默认文案，未显式调用时回落到 Model 的 systemPrompt，
     * 两者均未设置才使用内置默认——避免内置文案静默覆盖 Model 的角色设定。
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
     * 在调用此方法前，必须通过 {@link #model(DuoModel)} 设置模型。
     * </p>
     *
     * @return 配置完成的 DuoAgent 实例
     * @throws IllegalStateException 如果未设置 Model
     */
    public DuoAgent build() {
        if (model == null) {
            throw new IllegalStateException(
                    "未设置 Model。请调用 .model(DuoModel) 方法（如 DeepSeekModel.builder().build()）。"
            );
        }

        // 1. 解析 systemPrompt：Agent 显式 > Model > 内置默认
        var resolvedPrompt = this.systemPrompt != null
                ? this.systemPrompt
                : Objects.requireNonNullElse(model.getSystemPrompt(), DEFAULT_SYSTEM_PROMPT);

        // 2. 创建 LLM Runtime。HTTP 兜底超时按应用层最大超时（Agent llmTimeout
        //    与推理模式下 Model reasoningTimeout 的较大者）加 1 分钟余量计算——
        //    必须始终大于应用层超时，否则会先于应用层 barrier 掐断 SSE 流式回复。
        //    该约束只能在组装时刻计算（Model 不知道 Agent 的 llmTimeout），
        //    因此必须走带参工厂而非 Model 自用的无参工厂
        var reasoningBound = model.isReasoningEnabled() ? model.getReasoningTimeout() : Duration.ZERO;
        var appTimeout = this.timeout.compareTo(reasoningBound) > 0
                ? this.timeout : reasoningBound;
        var llmRuntime = new LlmRuntime();
        llmRuntime.registerAdapter(model.getApiFormat(), model.createAdapter(appTimeout.plusMinutes(1)));

        // 3. 创建工具注册表（按工具名去重）
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

        // 4. 创建 System Prompt（使用去重后的工具列表）
        var systemPromptImpl = new SystemPromptImpl(resolvedPrompt, false);
        systemPromptImpl.tools(assembly ->
                new ToolProviderResult(
                        uniqueTools.stream()
                                .map(t -> new ToolSchema(t.name(), t.description(), t.parameters()))
                                .toList()
                )
        );

        // 5. 创建 Session
        var sessionId = new SessionId("session-" + UUID.randomUUID().toString());
        var session = new Session(sessionId);

        // 6. 创建 Agent Options
        var agentOptions = new AgentOptions(
                model.getApiFormat(),     // API 格式
                model.getApiFormat(),     // provider（路由键与 API 格式一致）
                model.getModelName(),     // 模型名称
                model.getContextWindow(), // 上下文窗口
                model.getMaxOutputTokens(),    // 最大输出 token
                model.isReasoningEnabled(),    // 是否启用推理
                model.getReasoningTimeout(),   // 推理超时
                timeout,                 // 普通超时
                hooksBuilder.build()     // Hooks
        );

        // 7. 创建底层 Agent
        var agentId = new SessionId("agent-" + UUID.randomUUID().toString());
        var agent = new ReactLoopAgent(
                agentId,
                agentOptions,
                session,
                llmRuntime,
                systemPromptImpl,
                toolRegistry
        );

        // 8. 返回包装后的 DuoAgent
        return new DuoAgentImpl(agent, session);
    }
}
