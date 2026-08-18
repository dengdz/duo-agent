package dev.dsh.api.llm;

import dev.dsh.model.llm.GenerateOptions;
import dev.dsh.model.llm.StreamChunk;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 服务：适配器注册表 + 流式模型调用 API。
 * <p>
 * 对应 TS 源码中的 {@code LlmRuntime}。
 * </p>
 *
 * @author zhangyl
 * @date 2026-08-18
 */
public class LlmRuntime {

    private final Map<String, LlmAdapter> adapters = new ConcurrentHashMap<>();

    /**
     * 为给定提供方名称注册适配器。
     * @param provider 提供方路由键（例如 "deepseek-official"）
     * @param adapter 适配器实例
     * @throws IllegalArgumentException 如果该提供方已注册
     */
    public void registerAdapter(String provider, LlmAdapter adapter) {
        Objects.requireNonNull(provider, "provider 不能为 null");
        Objects.requireNonNull(adapter, "adapter 不能为 null");
        var previous = adapters.putIfAbsent(provider, adapter);
        if (previous != null) {
            throw new IllegalArgumentException("提供方 \"" + provider + "\" 已有注册的适配器");
        }
    }

    /**
     * 移除适配器注册。
     * @param provider 要取消注册的提供方路由键
     * @return 如果该提供方已注册并移除则返回 true
     */
    public boolean unregisterAdapter(String provider) {
        return adapters.remove(provider) != null;
    }

    /**
     * 将一次模型调用作为原始 chunk 流式输出。
     * <p>
     * 根据 {@code options.provider()} 选择适配器，
     * 委托给 {@link LlmAdapter#stream(GenerateOptions, StreamCallback)}。
     * </p>
     * @param options 完全组装好的请求
     * @param callback 流回调
     * @throws IllegalArgumentException 如果该提供方没有注册适配器
     */
    public void stream(GenerateOptions options, StreamCallback callback) {
        Objects.requireNonNull(options, "options 不能为 null");
        Objects.requireNonNull(callback, "callback 不能为 null");

        var adapter = adapters.get(options.provider());
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "提供方 \"" + options.provider() + "\" 没有注册的适配器"
            );
        }

        adapter.stream(options, callback);
    }
}