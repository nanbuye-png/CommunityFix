import { createRouter,createWebHistory } from 'vue-router'
import { useSession } from './stores/session'
export const router=createRouter({history:createWebHistory(),routes:[
  {path:'/login',component:()=>import('./views/Login.vue')},
  {path:'/',redirect:'/orders'},
  {path:'/orders',component:()=>import('./views/Workspace.vue'),meta:{permission:'repair:assign'}},
  {path:'/sla-warnings',component:()=>import('./views/SlaWarnings.vue'),meta:{role:'ADMIN'}},
  {path:'/notification-delivery',component:()=>import('./views/NotificationDelivery.vue'),meta:{role:'SUPER_ADMIN'}},
  {path:'/support',component:()=>import('./views/Support.vue'),meta:{manager:true}},
  {path:'/support-knowledge',component:()=>import('./views/SupportKnowledge.vue'),meta:{manager:true}},
  {path:'/workers',component:()=>import('./views/Workers.vue'),meta:{manager:true}},
  {path:'/community-users',component:()=>import('./views/CommunityUsers.vue'),meta:{role:'ADMIN'}},
  {path:'/:section(bindings|complaints|notices|notifications|users|roles|grants|logs|base)',component:()=>import('./views/Records.vue')}
]})
router.beforeEach(to=>{
 const session=useSession(),home=session.roles.includes('ADMIN')?'/orders':'/users'
 if(!session.loggedIn&&to.path!='/login')return '/login'
 if(session.loggedIn&&to.path==='/login')return home
 if(to.meta.role&&!session.roles.includes(String(to.meta.role)))return home
 if(to.meta.permission&&!session.can(String(to.meta.permission)))return home
 if(to.meta.manager&&!session.roles.some(r=>['ADMIN','SUPER_ADMIN'].includes(r)))return home
 const section=String(to.params.section||'')
 const permissions:Record<string,string>={users:'system:user',roles:'system:role',grants:'system:community',logs:'system:user'}
 if(permissions[section]&&!session.can(permissions[section]))return home
 if(['bindings','complaints','notices'].includes(section)&&!session.roles.includes('ADMIN'))return home
 if(section==='base'&&!session.roles.includes('ADMIN')&&!session.can('system:community'))return home
})
