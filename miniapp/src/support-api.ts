import { readSessionValue,expireSession } from './sessionStorage'
let base=import.meta.env.VITE_API_BASE||'/api'
// #ifdef H5
base=import.meta.env.VITE_H5_API_BASE||'/api'
// #endif
export function supportEvents(id:string,after:number){
 let task:UniApp.RequestTask|undefined
 const token=readSessionValue('token')
 const promise=new Promise<any>((resolve,reject)=>{task=uni.request({url:base+`/support/sessions/${id}/events`,data:{after},timeout:14000,header:{Authorization:`Bearer ${token}`},success(r){if(r.statusCode===200)resolve((r.data as any).data);else{if(r.statusCode===401)expireSession(token);reject({status:r.statusCode})}},fail:reject})})
 return {promise,cancel:()=>task?.abort()}
}
export const supportStates:Record<string,string>={AI:'AI 客服',WAITING:'等待人工接入',HUMAN:'人工接待中',CLOSED:'咨询已结束'}
