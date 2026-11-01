<script setup lang="ts">
import { computed } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { useSession } from './stores/session'
import { Wrench,LayoutDashboard,KeyRound,MessageSquare,Users,Building2,ShieldCheck,Bell,LogOut,House,ClipboardCheck,Contact,FileText,Clock3,Send } from 'lucide-vue-next'
const session=useSession(),route=useRoute(),router=useRouter()
const menus=computed(()=>[
  {path:'/support',name:'客服接待',icon:MessageSquare,show:session.roles.some(r=>['ADMIN','SUPER_ADMIN'].includes(r))},
  {path:'/support-knowledge',name:'客服知识库',icon:FileText,show:session.roles.some(r=>['ADMIN','SUPER_ADMIN'].includes(r))},
  {path:'/orders',name:'报修工作台',icon:LayoutDashboard,show:session.can('repair:assign')},
  {path:'/bindings',name:'房屋绑定审核',icon:KeyRound,show:session.roles.includes('ADMIN')},
  {path:'/workers',name:'维修人员',icon:Contact,show:session.roles.includes('ADMIN')||session.can('system:user')},
  {path:'/community-users',name:'居民账号',icon:Users,show:session.roles.includes('ADMIN')},
  {path:'/base',name:'小区与房屋',icon:Building2,show:session.roles.includes('ADMIN')||session.can('system:community')},
  {path:'/complaints',name:'投诉处理',icon:MessageSquare,show:session.roles.includes('ADMIN')},
  {path:'/sla-warnings',name:'工单时效预警',icon:Clock3,show:session.roles.includes('ADMIN')},
  {path:'/notices',name:'社区公告',icon:FileText,show:session.roles.includes('ADMIN')},
  {path:'/notifications',name:'消息通知',icon:Bell,show:true},
  {path:'/users',name:'用户管理',icon:Users,show:session.can('system:user')},
  {path:'/notification-delivery',name:'微信通知投递',icon:Send,show:session.roles.includes('SUPER_ADMIN')},
  {path:'/roles',name:'角色与权限',icon:ShieldCheck,show:session.can('system:role')},
  {path:'/grants',name:'小区授权',icon:House,show:session.can('system:community')},
  {path:'/logs',name:'操作记录',icon:ClipboardCheck,show:session.can('system:user')}
].filter(m=>m.show))
const title=computed(()=>menus.value.find(m=>m.path===route.path)?.name??'社区报修')
async function logout(){await session.logout();router.replace('/login')}
</script>
<style scoped>.sidebar nav{min-height:0;overflow-y:auto}.sidebar-bottom{flex-shrink:0}</style>
<template>
  <RouterView v-if="route.path==='/login'"/>
  <div v-else class="shell">
    <aside class="sidebar">
      <a class="brand" href="/" aria-label="CommunityFix 首页"><span class="brand-mark"><Wrench :size="22"/></span><span>CommunityFix<small>社区报修服务</small></span></a>
      <p class="nav-label">服务管理</p>
      <nav><RouterLink v-for="item in menus" :key="item.path" :to="item.path"><component :is="item.icon" :size="18"/><span>{{item.name}}</span></RouterLink></nav>
      <div class="sidebar-bottom"><span class="status-dot"></span>每一次报修，都有回应</div>
    </aside>
    <div class="main-shell">
      <header class="topbar"><div class="breadcrumb">社区服务 <span>/</span> <strong>{{title}}</strong></div><div class="account"><span class="avatar">{{session.user?.nickname?.slice(0,1)}}</span><span>{{session.user?.nickname}}<small>{{session.roles.includes('ADMIN')?'物业管理员':'系统管理员'}}</small></span><button class="icon-button" aria-label="退出登录" @click="logout"><LogOut :size="17"/></button></div></header>
      <main><RouterView :key="route.fullPath"/></main>
    </div>
  </div>
</template>
