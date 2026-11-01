import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'
export default defineConfig({plugins:[uni()],server:{host:'0.0.0.0',port:5174,strictPort:true,proxy:{'/api':{
  target:process.env.VITE_PROXY_TARGET||'http://127.0.0.1:8080',changeOrigin:true,
  configure(proxy){proxy.on('proxyReq',request=>request.removeHeader('origin'))}
}}}})
