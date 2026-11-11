import { defineStore } from 'pinia'
import { ref } from 'vue'
import { get,post,setSession } from '../api'
export const useSession=defineStore('session',()=>{
  const user=ref<any>(null)
  const permissions=ref<string[]>([])
  const roles=ref<string[]>([])
  const loggedIn=ref(false)
  function clear(){for(const k of Object.keys(sessionStorage))if(k.startsWith("cf_quote_"))sessionStorage.removeItem(k);user.value=null;permissions.value=[];roles.value=[];loggedIn.value=false;setSession('')}
  async function login(username:string,password:string){const result=await post('/auth/login',{username,password});if(!result.roles.some((r:string)=>['ADMIN','SUPER_ADMIN'].includes(r)))throw new Error('请使用物业或系统管理员账号登录后台');user.value=result.user;permissions.value=result.permissions;roles.value=result.roles;loggedIn.value=true;setSession(result.token,()=>{clear();window.location.assign('/login')})}
  async function logout(){try{await post('/auth/logout')}finally{clear()}}
  function can(permission:string){return permissions.value.includes(permission)}
  return {user,permissions,roles,loggedIn,clear,login,logout,can}
})
