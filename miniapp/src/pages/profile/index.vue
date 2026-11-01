<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { useSession } from '../../stores/session'
import { backToPreviousTab,noteTabShown } from '../../tabNavigation'
import { request } from '../../api'
const session=useSession()
const orderTemplateId=import.meta.env.VITE_WECHAT_ORDER_TEMPLATE_ID||''
async function subscribe(){
  if(!orderTemplateId)return
  // #ifdef MP-WEIXIN
  try{
    const result=await new Promise<any>((resolve,reject)=>uni.requestSubscribeMessage({tmplIds:[orderTemplateId],success:resolve,fail:reject}))
    if(result[orderTemplateId]!=='accept'){uni.showToast({title:'未开启工单微信提醒',icon:'none'});return}
    await request('/notifications/wechat-subscription','POST',{templateId:orderTemplateId})
    uni.showToast({title:'已订阅一次接单成功通知',icon:'none'})
  }catch(error){console.warn('订阅消息授权未完成',error);uni.showToast({title:'订阅失败，请稍后重试',icon:'none'})}
  // #endif
}
onShow(()=>{if(session.requireLogin())noteTabShown('/pages/profile/index')})
</script>
<template><view class="page"><view class="back-link" @click="backToPreviousTab('/pages/profile/index')">‹ 返回上一步</view><view class="eyebrow">我的社区服务</view><view class="page-title">个人中心</view><view class="card profile"><view class="avatar">{{session.user?.nickname?.slice(0,1)}}</view><view><view class="order-title">{{session.user?.nickname}}</view><view class="muted">{{session.activeRole==='WORKER'?'维修人员':'社区居民'}}</view></view></view><view class="card"><view v-if="session.roles.includes('RESIDENT')" class="entry" @click="uni.navigateTo({url:'/pages/bindings/index'})">房屋绑定 <text>→</text></view><view v-if="session.activeRole==='RESIDENT'" class="entry" @click="uni.navigateTo({url:'/pages/complaints/index'})">投诉处理进度 <text>→</text></view><view v-if="session.roles.includes('WORKER')" class="entry" @click="uni.navigateTo({url:'/pages/history/index'})">历史维修记录 <text>→</text></view><template v-if="session.roles.includes('RESIDENT')&&session.roles.includes('WORKER')"><view class="entry" @click="session.switchRole(session.activeRole==='RESIDENT'?'WORKER':'RESIDENT')">切换为{{session.activeRole==='RESIDENT'?'维修人员':'居民'}} <text>→</text></view></template></view><button v-if="orderTemplateId&&session.activeRole==='RESIDENT'" class="secondary" @click="subscribe">订阅接单成功通知</button><button class="secondary" @click="session.logout">退出登录</button><view class="small spaced" style="text-align:center">CommunityFix · 社区便民报修服务</view></view></template>
<style scoped>.profile{display:flex;gap:26rpx;align-items:center}.avatar{width:90rpx;height:90rpx;background:#e0f1e9;color:#0b7774;display:flex;align-items:center;justify-content:center;border-radius:50%;font-size:36rpx}.entry{display:flex;align-items:center;justify-content:space-between;padding:28rpx 0;border-bottom:1rpx solid #edf2f2;color:#587d83}.entry:last-child{border:none}.entry text{color:#9ab5b4}</style>
