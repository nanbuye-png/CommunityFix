<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Wrench,ArrowRight,House,CheckCircle2 } from 'lucide-vue-next'
import { useSession } from '../stores/session'
const session=useSession(),router=useRouter(),username=ref(''),password=ref(''),busy=ref(false),error=ref('')
async function login(){if(!username.value||!password.value){error.value='请填写账号和密码';return}busy.value=true;error.value='';try{await session.login(username.value,password.value);router.push(session.roles.includes('ADMIN')?'/orders':'/users')}catch(e){if(e instanceof Error&&!('response' in e))error.value=e.message}finally{busy.value=false}}
</script>
<template><div class="login-layout"><section class="login-story"><div class="brand light"><span class="brand-mark"><Wrench/></span><span>CommunityFix<small>社区报修服务</small></span></div><div class="story-copy"><span class="eyebrow">把社区的日常，照顾好</span><h1>从一声报修，<br>到一次安心。</h1><p>串联居民、物业与维修人员，<br>让问题有人接，进度看得见。</p><div class="service-relay"><span><House :size="18"/>居民报修</span><i></i><span><Wrench :size="18"/>维修处理</span><i></i><span><CheckCircle2 :size="18"/>居民验收</span></div></div><small class="story-footer">社区便民报修服务系统 · 服务管理平台</small></section><section class="login-form"><div class="login-box"><span class="eyebrow">管理工作台</span><h2>欢迎回来</h2><p class="muted">登录后查看社区报修与待办事项</p><form @submit.prevent="login"><label>管理员账号<el-input v-model="username" size="large" placeholder="请输入账号" autocomplete="username"/></label><label>密码<el-input v-model="password" size="large" type="password" show-password placeholder="请输入密码" autocomplete="current-password"/></label><p v-if="error" class="error-text" role="alert">{{error}}</p><el-button type="primary" size="large" native-type="submit" :loading="busy" class="full">进入工作台 <ArrowRight :size="16"/></el-button></form><p class="login-hint">居民与维修人员请使用社区报修小程序</p></div></section></div></template>
