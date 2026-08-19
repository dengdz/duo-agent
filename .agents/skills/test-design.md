---
name: test-design
description: Design comprehensive unit tests for Java code
---
# Test Design Skill

设计 Java 单元测试时遵循以下原则：

## 测试结构

1. **AAA 模式**
   - Arrange（准备）：初始化测试数据
   - Act（执行）：调用被测方法
   - Assert（断言）：验证结果

2. **命名规范**
   - 测试方法名：should_ExpectedBehavior_When_Condition
   - 示例：should_ThrowException_When_InputIsNull

3. **测试覆盖**
   - 正常路径（Happy Path）
   - 边界条件（Boundary）
   - 异常路径（Exception）

4. **Mock 使用**
   - 使用 Mockito 隔离依赖
   - 验证交互次数和参数

## 断言推荐

- JUnit 5 Assertions
- AssertJ 流式断言

确保测试独立、可重复、快速执行。
