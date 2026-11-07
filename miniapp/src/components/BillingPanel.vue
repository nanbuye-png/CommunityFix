<script setup lang="ts">
import { ref,computed,watch,onMounted,onUnmounted } from 'vue'
import { request,idempotencyKey } from '../api'
const props=defineProps<{order:any,owner:boolean}>(),data=ref<any>(null),busy=ref(false)
const money=(n:any)=>(Number(n)/100).toFixed(2)
const names:Record<string,string>={PROPOSED:'待确认',ACCEPTED:'已确认',REJECTED:'已拒绝',SUPERSEDED:'已替换'}
function lines(q:any){try{return typeof q.itemsJson==='string'?JSON.parse(q.itemsJson):q.itemsJson}catch{return []}}
let requestVersion=0
const now=ref(Date.now()), serverOffset=ref(0)
const attempt=computed(()=>data.value?.paymentAttempt)
const remaining=computed(()=>attempt.value?.status==='PENDING'
  ? Math.max(0,Math.ceil((Date.parse(attempt.value.expiresAt)-now.value-serverOffset.value)/1000)) : 0)
const countdown=computed(()=>Math.floor(remaining.value/60)+':'+String(remaining.value%60).padStart(2,'0'))
watch(()=>remaining.value>0,(running,_,onCleanup)=>{
  if(running){
    const timer=setInterval(()=>{now.value=Date.now()},500)
    onCleanup(()=>clearInterval(timer))
  }
})
async function load(){
  const version=++requestVersion
  const result=await request(`/repair/orders/${props.order.id}/billing`)
  if(version!==requestVersion)return
  now.value=Date.now()
  serverOffset.value=Date.parse(result.serverTime)-now.value
  data.value=result
}
const liveRefresh=()=>{load().catch(()=>{})}
onMounted(()=>uni.$on('cf-notifications',liveRefresh))
onUnmounted(()=>{requestVersion++;uni.$off('cf-notifications',liveRefresh)})
// The detail page fetches a fresh order on each onShow, even at the same version.
watch(()=>props.order,liveRefresh,{immediate:true})
async function submit(path:string,body:any){
  if(busy.value)return
  busy.value=true
  try{
    await request(`/repair/orders/${props.order.id}/billing/${path}`,'POST',body,idempotencyKey())
  }catch{
    // The request helper displays the error; reload in case only the response was lost.
  }finally{
    try{await load()}finally{busy.value=false}
  }
}
function startPayment(){return submit('payment-attempts',{billId:data.value.bill.id})}
function confirmPayment(){return submit('mock-pay',{billId:data.value.bill.id,attemptNo:attempt.value.attemptNo})}
</script>
<template><view id="billing-panel" class="card"><view class="section-title">维修费用</view><view v-if="!order.billingVersion">历史工单，不补收费用。</view><view v-else-if="order.facilityScope==='PUBLIC'">公共设施 · 居民无需支付</view><template v-else-if="data"><view>先确认报价，维修验收后支付。</view><view v-if="data.acceptedQuote" class="notice">已确认金额 ¥{{money(data.acceptedQuote.amountFen)}}</view><view v-for="q in data.quotes" :key="q.id" class="spaced"><view>第{{q.revision}}版 · ¥{{money(q.amountFen)}} · {{names[q.status]}}</view><view v-for="(line,i) in lines(q)" :key="i" class="small">{{line.name}} × {{line.quantity}} · 单价 ¥{{money(line.unitPriceFen)}}</view><view class="muted">{{q.reason}}</view><template v-if="owner&&q.status==='PROPOSED'&&!['CANCELED','REJECTED','CLOSED'].includes(order.status)"><button class="primary" :disabled="busy" @click="submit('decision',{quoteId:q.id,accepted:true})">确认本版完整报价</button><button class="secondary" :disabled="busy" @click="submit('decision',{quoteId:q.id,accepted:false})">拒绝本版报价</button></template></view><view v-if="data.bill" class="notice">账单 ¥{{money(data.bill.amountFen)}} · {{data.bill.status==='MOCK_PAID'?'模拟支付完成，未真实扣款':'待支付'}}<view v-if="owner&&data.bill.status==='UNPAID'&&data.mockEnabled">
  <template v-if="remaining>0">
    <view>剩余支付时间 {{countdown}}</view>
    <button class="primary" :disabled="busy" @click="confirmPayment">确认模拟支付（不扣款）</button>
  </template>
  <template v-else>
    <view v-if="attempt">本次支付已超时</view>
    <button class="primary" :disabled="busy" @click="startPayment">{{attempt?'重新支付':'去支付'}}</button>
  </template>
</view></view><view v-if="!data.quotes.length" class="muted">等待物业报价。</view><button class="secondary" @click="load">刷新费用进度</button></template></view></template>
