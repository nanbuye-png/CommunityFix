<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { request } from '../../api'
import { useSession } from '../../stores/session'
const session=useSession(),username=ref(''),password=ref(''),nickname=ref(''),busy=ref(false),account=ref(false),entry=ref(''),registering=ref(false),communityId=ref<number|null>(null),communities=ref<any[]>([])
onLoad(options=>{entry.value=['RESIDENT','WORKER'].includes(String(options?.role))?String(options?.role):''})
// #ifdef H5
account.value=true
// #endif
async function login(){if(!entry.value){uni.showToast({title:'请先选择居民或维修人员入口',icon:'none'});return}busy.value=true;try{let result;if(account.value)result=await request('/auth/login','POST',{username:username.value,password:password.value});else{const response=await new Promise<UniApp.LoginRes>((resolve,reject)=>uni.login({provider:'weixin',success:resolve,fail:reject}));result=await request('/auth/wechat-login','POST',{code:response.code})}session.save(result,entry.value);uni.reLaunch({url:'/pages/home/index'})}catch{}finally{busy.value=false}}
async function register(){
 if(busy.value)return
 if(!entry.value){uni.showToast({title:'请先选择身份',icon:'none'});return}
 if(!/^[a-zA-Z0-9_]{3,50}$/.test(username.value)||!nickname.value.trim()||[...password.value].length<12||[...password.value].reduce((n,c)=>n+(c.codePointAt(0)!<=127?1:c.codePointAt(0)!<=2047?2:c.codePointAt(0)!<=65535?3:4),0)>72||entry.value==='WORKER'&&!communityId.value){uni.showToast({title:'请填写账号、姓名、合规密码及申请小区',icon:'none'});return}
 busy.value=true
 try{const result=await request('/auth/register','POST',{username:username.value,password:password.value,nickname:nickname.value.trim(),role:entry.value,communityId:entry.value==='WORKER'?communityId.value:null});session.save(result,entry.value);uni.reLaunch({url:'/pages/home/index'})}catch{}finally{busy.value=false}
}
async function registration(){registering.value=true;account.value=true;try{if(!communities.value.length)communities.value=await request('/auth/registration-communities')}catch{}}
</script>
<template><view class="page login"><view class="logo">CF</view><view class="eyebrow">COMMUNITYFIX · 社区报修</view><view class="page-title">让家里的问题，<br>有人认真回应。</view><view class="muted">提交报修、跟进维修、确认结果，<br>一个入口，连接社区服务。</view><view class="card spaced"><view class="tab-row"><text :class="{active:entry==='RESIDENT'}" @click="entry='RESIDENT'">居民入口</text><text :class="{active:entry==='WORKER'}" @click="entry='WORKER'">维修人员入口</text></view><view class="muted">{{entry==='WORKER'?'维修人员 · 接单与处理任务':entry==='RESIDENT'?'居民 · 报修与跟进服务':'请选择身份'}}</view><template v-if="account||registering"><view class="label">账号</view><input v-model="username" class="field" placeholder="3至50位字母、数字或下划线"/><view v-if="registering" class="label">姓名 / 昵称</view><input v-if="registering" v-model="nickname" class="field" maxlength="50" placeholder="请输入姓名或昵称"/><view class="label">密码</view><input v-model="password" class="field" password placeholder="至少12位密码"/><template v-if="registering&&entry==='WORKER'"><view class="label">申请服务小区</view><picker :range="communities" range-key="name" @change="communityId=communities[Number($event.detail.value)].id"><view class="field">{{communities.find(c=>c.id===communityId)?.name||'请选择申请小区'}}</view></picker><view class="notice">注册后可登录查看页面；物业确认技能并启用账号后才能接单。</view></template></template><button class="primary" :loading="busy" :disabled="busy" @click="registering?register():login()">{{registering?'创建账号':account?'账号登录':'微信登录'}}</button><button class="secondary" @click="registering?registering=false:registration()">{{registering?'返回登录':'创建居民或维修账号'}}</button><button v-if="!registering" class="secondary" @click="account=!account">{{account?'使用微信登录':'使用账号登录'}}</button></view><view class="notice">H5 可在两个标签页分别登录，刷新保留各自身份。</view></view></template>
<style scoped>.login{padding-top:100rpx}.logo{width:100rpx;height:100rpx;background:#0b5354;color:#fff;border-radius:28rpx 28rpx 28rpx 6rpx;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:38rpx;margin-bottom:45rpx}.page-title{line-height:1.6;margin-top:28rpx}.card{margin-top:65rpx}</style>
