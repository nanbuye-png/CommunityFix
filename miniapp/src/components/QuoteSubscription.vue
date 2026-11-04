<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { request } from '../api'
const template=ref(''),busy=ref(false)
onMounted(async()=>{try{template.value=(await request('/notifications/wechat-subscription')).quoteTemplateId||''}catch{}})
async function subscribe(){
 // #ifdef MP-WEIXIN
 if(!template.value||busy.value)return
 busy.value=true
 try{const result=await new Promise<any>((resolve,reject)=>uni.requestSubscribeMessage({tmplIds:[template.value],success:resolve,fail:reject}));if(result[template.value]==='accept'){await request('/notifications/wechat-subscription','POST',{templateId:template.value});uni.showToast({title:'已授权一次后续报价提醒',icon:'none'})}else uni.showToast({title:'未授权，可在站内消息查看报价',icon:'none'})}catch{uni.showToast({title:'订阅未完成，请重试或查看站内消息',icon:'none'})}finally{busy.value=false}
 // #endif
 // #ifdef H5
 uni.showToast({title:'请在微信小程序中授权订阅，网页使用在线通知',icon:'none'})
 // #endif
}
</script>
<template><view v-if="template" class="notice"><button class="secondary" :disabled="busy" @click="subscribe">订阅一次后续报价微信提醒</button><view class="small">微信通知标题为“新订单通知”，用于提醒报价待确认。点击通知查看费用明细并确认或拒绝；授权仅用于之后产生的新报价。</view></view></template>
