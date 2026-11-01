import { defineStore } from 'pinia'
import { ref } from 'vue'
import { request } from '../api'
import { resetTabHistory } from '../tabNavigation'
import { readSessionValue, writeSessionValue, clearSessionValues } from '../sessionStorage'
export const useSession=defineStore('session',()=>{
 const user=ref<any>(null),roles=ref<string[]>([]),activeRole=ref('RESIDENT')
 function restore(){
  const saved=readSessionValue('session')
  if(saved && readSessionValue('token')){user.value=saved.user;roles.value=saved.roles;activeRole.value=saved.activeRole}
  else{user.value=null;roles.value=[];activeRole.value='RESIDENT'}
 }
 function save(result:any, selectedRole?:string){
  const role=selectedRole || (result.roles.includes('RESIDENT')?'RESIDENT':'WORKER')
  if(!['RESIDENT','WORKER'].includes(role)||!result.roles.includes(role)){
   uni.showToast({title:role==='WORKER'?'请使用维修人员账号登录此入口':'请使用居民账号登录此入口',icon:'none'})
   throw new Error('Wrong role')
  }
  resetTabHistory();user.value=result.user;roles.value=result.roles;activeRole.value=role
  writeSessionValue('token',result.token);persist()
 }
 function persist(){writeSessionValue('session',{user:user.value,roles:roles.value,activeRole:activeRole.value})}
 function switchRole(role:string){if(!roles.value.includes(role))return;resetTabHistory();activeRole.value=role;persist();uni.reLaunch({url:'/pages/home/index'})}
 function requireLogin(requiredRole?:string){
  restore()
  if(!user.value||!readSessionValue('token')){uni.reLaunch({url:'/pages/login/index'});return false}
  if(requiredRole && activeRole.value!==requiredRole){uni.showToast({title:'当前身份无法访问此页面',icon:'none'});uni.reLaunch({url:'/pages/home/index'});return false}
  return true
 }
 async function logout(){try{await request('/auth/logout','POST')}finally{resetTabHistory();clearSessionValues();restore();uni.reLaunch({url:'/pages/login/index'})}}
 return {user,roles,activeRole,restore,save,switchRole,requireLogin,logout}
})
