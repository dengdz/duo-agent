import { defineConfig } from 'vitepress'

// 项目 Pages（非用户根站点）：资源路径必须带 /duo-agent/ 前缀；
// 绑定自定义域名（CNAME）后改为 '/'
const base = '/duo-agent/'

export default defineConfig({
  title: 'duo-agent',
  description: '零依赖的 Java 21 AI Agent SDK',
  lang: 'zh-CN',
  base,
  // 内容源是 website/docs：CI 构建前从仓库根 docs/ 复制（见 docs.yml），
  // 本地开发用 symlink（website/docs -> ../docs）指向同一份源文件
  srcDir: 'docs',
  // docs/ 中存在指向仓库根的相对链接（../README.md、../duo-agent-example/...），
  // 网站上无对应路由：放行死链检查（GitHub 上仍可正常点击）
  ignoreDeadLinks: true,
  head: [['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}favicon.svg` }]],
  themeConfig: {
    nav: [
      { text: '快速开始', link: '/01-getting-started/quick-start' },
      { text: '指南', link: '/02-guide/chat-api' },
      { text: '高级', link: '/03-advanced/hooks' },
      { text: '架构', link: '/04-architecture/overview' },
      { text: 'GitHub', link: 'https://github.com/dengdz/duo-agent' }
    ],
    sidebar: [
      {
        text: '入门',
        items: [
          { text: '简介', link: '/01-getting-started/introduction' },
          { text: '快速开始', link: '/01-getting-started/quick-start' }
        ]
      },
      {
        text: '指南',
        items: [
          { text: '对话 API', link: '/02-guide/chat-api' },
          { text: '流式输出', link: '/02-guide/streaming' },
          { text: '多厂商接入', link: '/02-guide/multi-provider' },
          { text: '内置工具', link: '/02-guide/tools-builtin' },
          { text: '自定义工具', link: '/02-guide/tools-custom' },
          { text: '推理模型', link: '/02-guide/reasoning-models' },
          { text: '取消与中断', link: '/02-guide/cancellation' },
          { text: 'Spring Boot SSE 桥接', link: '/02-guide/spring-sse' }
        ]
      },
      {
        text: '高级',
        items: [
          { text: 'Hook 扩展点', link: '/03-advanced/hooks' },
          { text: 'LLM 自动重试', link: '/03-advanced/retry' },
          { text: '上下文压缩', link: '/03-advanced/compaction' },
          { text: '会话持久化', link: '/03-advanced/session-persistence' },
          { text: 'Skill 系统', link: '/03-advanced/skills' }
        ]
      },
      {
        text: '架构',
        items: [
          { text: '架构总览', link: '/04-architecture/overview' },
          { text: '事件溯源', link: '/04-architecture/event-sourcing' },
          { text: 'ReAct 循环', link: '/04-architecture/react-loop' },
          { text: 'SDK 设计', link: '/04-architecture/sdk-design' }
        ]
      },
      {
        text: '参考',
        items: [
          { text: '事件类型参考', link: '/05-reference/events' },
          { text: '已知限制与路线图', link: '/05-reference/limitations' },
          { text: '术语表', link: '/05-reference/glossary' }
        ]
      }
    ],
    socialLinks: [{ icon: 'github', link: 'https://github.com/dengdz/duo-agent' }],
    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: '搜索文档', buttonAriaLabel: '搜索' },
          modal: {
            displayDetails: '显示详情',
            resetButtonTitle: '清除',
            backButtonTitle: '返回',
            noResultsText: '无结果',
            footer: { selectText: '选择', navigateText: '切换', closeText: '关闭' }
          }
        }
      }
    },
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一页', next: '下一页' },
    returnToTopLabel: '回到顶部',
    sidebarMenuLabel: '目录',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到亮色',
    darkModeSwitchTitle: '切换到暗色'
  }
})
