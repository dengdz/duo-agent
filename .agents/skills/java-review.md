---
name: java-review
description: Review Java code following Alibaba coding guidelines
---
# Java Code Review Skill

审查 Java 代码时，重点关注以下方面：

## 阿里巴巴规范检查项

1. **命名规范**
   - 类名使用 UpperCamelCase
   - 方法名、参数名、成员变量使用 lowerCamelCase
   - 常量名全大写，单词间用下划线隔开
   - 抽象类命名使用 Abstract 或 Base 开头
   - 异常类命名使用 Exception 结尾

2. **注释规范**
   - 所有类必须添加创建者和日期
   - 公有方法必须使用 Javadoc 注释
   - 复杂算法和业务逻辑必须添加注释

3. **并发控制**
   - 使用 ConcurrentHashMap 代替 Hashtable
   - 避免在循环中创建线程
   - 正确使用 volatile 关键字

4. **异常处理**
   - 不要捕获 Exception 和 Throwable
   - 不要在 finally 块中使用 return
   - 资源必须在 try-with-resources 中管理

给出具体的修改建议和示例代码。
