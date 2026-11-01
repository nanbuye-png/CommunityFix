const configured = process.env.VITE_API_BASE?.trim()

if (!configured) {
  console.error('微信小程序构建需要 VITE_API_BASE，例如 https://repair.example.com/api；手机联调不能使用相对路径 /api。')
  process.exit(1)
}

let url
try {
  url = new URL(configured)
} catch {
  console.error('VITE_API_BASE 必须是完整的 http(s) URL。')
  process.exit(1)
}

if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || url.pathname !== '/api') {
  console.error('VITE_API_BASE 应为不含账号、查询参数的完整 API 地址，并以 /api 结尾。')
  process.exit(1)
}

if (['localhost', '127.0.0.1', '::1'].includes(url.hostname)) {
  console.warn('当前 API 地址只适用于电脑模拟器；手机联调请使用可从手机访问的局域网地址或 HTTPS 域名。')
}
