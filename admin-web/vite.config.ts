import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
export default defineConfig({plugins:[vue()],server:{host:'0.0.0.0',port:5173,strictPort:true,proxy:{'/api':{
  target:process.env.VITE_PROXY_TARGET||'http://127.0.0.1:8080',changeOrigin:true,
  // Requests reach this development proxy on the page's own origin, including
  // LAN hosts. Do not forward that browser origin as a cross-origin API call.
  configure(proxy){proxy.on('proxyReq',request=>request.removeHeader('origin'))}
}}}})
