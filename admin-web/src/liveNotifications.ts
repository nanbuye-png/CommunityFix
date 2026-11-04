import { ref } from 'vue'
import { ElNotification } from 'element-plus'
export const notificationUnread=ref(0)
export const notificationOnline=ref(false)
let socket:WebSocket|undefined,timer:ReturnType<typeof setTimeout>|undefined,watchdog:ReturnType<typeof setTimeout>|undefined,generation=0
export function stopNotifications(){generation++;clearTimeout(timer);clearTimeout(watchdog);socket?.close();socket=undefined;notificationUnread.value=0;notificationOnline.value=false}
export function startNotifications(token:string,onExpired:()=>void){
 stopNotifications();if(!token)return
 const run=generation;let attempt=0,last:string|undefined
 function connect(){
  if(run!==generation)return
  const ws=new WebSocket(`${location.protocol==='https:'?'wss':'ws'}://${location.host}/api/notifications/live`);socket=ws;let ended=false
  function heartbeat(){clearTimeout(watchdog);watchdog=setTimeout(()=>ws.close(),15000)}
  ws.onopen=()=>{if(run!==generation){ws.close();return}ws.send(JSON.stringify({type:'auth',token}));heartbeat()}
  ws.onmessage=event=>{
   if(run!==generation)return
   try{const data=JSON.parse(event.data);if(data.type!=='notifications')return
    const previousUnread=notificationUnread.value;heartbeat();attempt=0;notificationOnline.value=true;notificationUnread.value=Number(data.unread)||0
    if(last!==undefined&&last!==data.latestId&&data.latestId!=='0')ElNotification({title:'收到新消息',message:'请到消息通知查看服务进展。',type:'info'})
    if(last!==data.latestId||previousUnread!==notificationUnread.value)window.dispatchEvent(new Event('cf-notifications'))
    last=data.latestId
   }catch{ws.close()}
  }
  function disconnected(code:number){if(run!==generation||ended)return;ended=true;clearTimeout(watchdog);notificationOnline.value=false
   if(code===1008){stopNotifications();onExpired();return}
   timer=setTimeout(connect,Math.min(30000,1000*2**Math.min(attempt++,5))+Math.random()*500)
  }
  ws.onclose=event=>disconnected(event.code)
  ws.onerror=()=>{ws.close();disconnected(0)}
 }
 connect()
}
