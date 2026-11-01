import { readSessionValue, expireSession } from './sessionStorage'
let API=import.meta.env.VITE_API_BASE||'/api'
// #ifdef H5
// H5 shares its page origin via /api. A WeChat phone-build address left in the
// terminal must not redirect desktop browser calls to another origin.
API=import.meta.env.VITE_H5_API_BASE||'/api'
// #endif
export function idempotencyKey(){return 'cf_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,14)}
function failed(message:string){uni.showToast({title:message,icon:'none',duration:2500})}
export function request<T=any>(path:string,method:'GET'|'POST'|'DELETE'='GET',data?:any,key?:string):Promise<T>{
 const token=readSessionValue('token')
 return new Promise((resolve,reject)=>{uni.request({url:API+path,method,data,timeout:15000,header:{'Content-Type':'application/json',...(token?{Authorization:`Bearer ${token}`} :{}),...(key?{'Idempotency-Key':key}:{})},success(result){const body=result.data as any;if(result.statusCode===200){resolve(body.data);return}if(result.statusCode===401){expireSession(token)}failed(body?.message||'操作失败，请稍后重试');reject({status:result.statusCode,...body})},fail(error){console.error('CommunityFix API request failed',API+path,error.errMsg);failed('网络连接失败，请检查网络后重试');reject(error)}})})
}
export function upload(path:string):Promise<string>{const token=readSessionValue('token');return new Promise((resolve,reject)=>uni.uploadFile({url:API+'/files',filePath:path,name:'file',header:{Authorization:`Bearer ${token}`},success(result){try{const body=JSON.parse(result.data);if(result.statusCode!==200){if(result.statusCode===401)expireSession(token);failed(body.message||'图片上传失败');reject(body)}else resolve(body.data.attachmentId)}catch(error){reject(error)}},fail(error){failed('图片上传失败，请重新选择');reject(error)}}))}
export const statuses:Record<string,string>={SUBMITTED:'待审核',REJECTED:'已驳回',PENDING_DISPATCH:'待派单',ASSIGNED:'待接单',ACCEPTED:'已接单',IN_PROGRESS:'维修中',PENDING_CONFIRM:'待验收',CLOSED:'已完成',CANCELED:'已取消'}
export const priorities:Record<string,string>={NORMAL:'普通',HIGH:'较高',URGENT:'紧急'}
export function date(value:string){if(!value)return'—';const d=new Date(new Date(value).getTime()+8*3600*1000);return `${d.getUTCMonth()+1}月${d.getUTCDate()}日 ${String(d.getUTCHours()).padStart(2,'0')}:${String(d.getUTCMinutes()).padStart(2,'0')}`}
