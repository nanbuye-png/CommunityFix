import { ref } from 'vue'
export const notificationConnection=ref('连接中')
let task:UniApp.SocketTask|undefined,timer:ReturnType<typeof setTimeout>|undefined,watchdog:ReturnType<typeof setTimeout>|undefined,generation=0,current=''
export function stopNotifications(){generation++;current='';notificationConnection.value='未连接';clearTimeout(timer);clearTimeout(watchdog);task?.close({});task=undefined;uni.removeTabBarBadge({index:2,fail:()=>{}})}
export function startNotifications(token:string,onExpired:()=>void){
 if(token&&current===token)return
 stopNotifications();if(!token)return
 current=token;notificationConnection.value='连接中';const run=generation;let attempt=0,last:string|undefined,lastUnread=-1
 let base=import.meta.env.VITE_API_BASE||'/api'
 // #ifdef H5
 base=new URL(import.meta.env.VITE_H5_API_BASE||'/api',window.location.origin).href
 // #endif
 const url=base.replace(/^http/,'ws').replace(/\/$/,'')+'/notifications/live'
 function connect(){
  if(run!==generation)return
  const ws=uni.connectSocket({url,complete:()=>{}});task=ws;let ended=false
  function heartbeat(){clearTimeout(watchdog);watchdog=setTimeout(()=>ws.close({}),15000)}
  ws.onOpen(()=>{if(run!==generation){ws.close({});return}ws.send({data:JSON.stringify({type:'auth',token})});heartbeat()})
  ws.onMessage(event=>{if(run!==generation)return
   try{const data=JSON.parse(String(event.data));if(data.type!=='notifications')return
    heartbeat();attempt=0;notificationConnection.value='已连接'
    if(Number(data.unread)>0)uni.setTabBarBadge({index:2,text:Number(data.unread)>99?'99+':String(data.unread),fail:()=>{}})
    else uni.removeTabBarBadge({index:2,fail:()=>{}})
    if(last!==undefined&&last!==data.latestId&&data.latestId!=='0')uni.showToast({title:'收到新消息，请到消息页查看',icon:'none'})
    if(last!==data.latestId||lastUnread!==Number(data.unread))uni.$emit('cf-notifications');last=data.latestId;lastUnread=Number(data.unread)
   }catch{ws.close({})}
  })
  function disconnected(code:number){if(run!==generation||ended)return;ended=true;notificationConnection.value='重连中，可手动刷新';clearTimeout(watchdog)
   if(code===1008){stopNotifications();onExpired();return}
   timer=setTimeout(connect,Math.min(30000,1000*2**Math.min(attempt++,5))+Math.random()*500)
  }
  ws.onClose(event=>disconnected(event.code))
  ws.onError(()=>{ws.close({});disconnected(0)})
 }
 connect()
}
