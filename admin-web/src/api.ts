import axios from 'axios'
import { ElMessage } from 'element-plus'
export const http=axios.create({baseURL:'/api',timeout:15000})
let token=''
let expired:()=>void=()=>{}
export function setSession(value:string,onExpire?:()=>void){token=value;if(onExpire)expired=onExpire}
http.interceptors.request.use(config=>{if(token)config.headers.Authorization=`Bearer ${token}`;return config})
http.interceptors.response.use(response=>response.data.data,error=>{if(error.response?.status===401)expired();if(error.code!=='ERR_CANCELED'&&!error.config?.url?.endsWith('/events'))ElMessage.error(error.response?.data?.message??'无法连接服务，请检查网络后重试');return Promise.reject(error)})
export function key(){
  const random=globalThis.crypto
  if(random?.randomUUID)return random.randomUUID()
  if(random?.getRandomValues)return 'cf_'+Array.from(random.getRandomValues(new Uint32Array(4)),value=>value.toString(16).padStart(8,'0')).join('')
  return 'cf_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2)+Math.random().toString(36).slice(2)
}
export function get<T=any>(path:string,params?:Record<string,unknown>):Promise<T>{return http.get(path,{params}) as Promise<T>}
export function post<T=any>(path:string,data?:unknown,idempotencyKey?:string):Promise<T>{return http.post(path,data,{headers:idempotencyKey?{'Idempotency-Key':idempotencyKey}:{}}) as Promise<T>}
export function patch<T=any>(path:string,data:unknown):Promise<T>{return http.patch(path,data) as Promise<T>}
export function remove(path:string){return http.delete(path)}
export const statusNames:Record<string,string>={SUBMITTED:'待审核',REJECTED:'已驳回',PENDING_DISPATCH:'待派单',ASSIGNED:'待接单',ACCEPTED:'已接单',IN_PROGRESS:'维修中',PENDING_CONFIRM:'待验收',CLOSED:'已完成',CANCELED:'已取消'}
export const priorityNames:Record<string,string>={NORMAL:'普通',HIGH:'较高',URGENT:'紧急'}
export function date(value?:string){return value?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(value)):'—'}
