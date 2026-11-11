<script setup lang="ts">
import { ref,onMounted } from 'vue'
import { request } from '../api'
const templates=ref<string[]>([]),busy=ref(false)
onMounted(async()=>{try{const config=await request('/notifications/wechat-subscription');templates.value=[config.quoteTemplateId,config.paymentTemplateId].filter(Boolean)}catch{}})
async function subscribe(){
 // #ifdef MP-WEIXIN
 if(!templates.value.length||busy.value)return
 busy.value=true
 try{const result=await new Promise<any>((resolve,reject)=>uni.requestSubscribeMessage({tmplIds:templates.value,success:resolve,fail:reject}));const accepted=templates.value.filter(t=>result[t]==='accept');for(const templateId of accepted)await request('/notifications/wechat-subscription','POST',{templateId});uni.showToast({title:accepted.length?`已授权${accepted.length}种费用提醒`:'未授权，可在站内查看提醒',icon:'none'})}catch{uni.showToast({title:'订阅未完成，请重试或查看站内消息',icon:'none'})}finally{busy.value=false}
 // #endif
 // #ifdef H5
 uni.showToast({title:'请在微信小程序中授权订阅，网页使用在线通知',icon:'none'})
 // #endif
}
</script>
<template><view v-if="templates.length" class="notice"><button class="secondary" :disabled="busy" @click="subscribe">订阅报价与待支付提醒</button><view class="small">报价待确认时发送“订单确认提醒”，验收后有待付账单时发送“待支付提醒”。每个模板各授权一次，点击通知可进入费用明细；授权不补发之前的提醒。</view></view></template>
