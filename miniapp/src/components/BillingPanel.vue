<script setup lang="ts">
import { ref,computed,watch,onMounted,onUnmounted } from 'vue'
import { request,idempotencyKey } from '../api'
import { useSession } from '../stores/session'
import { readSessionValue,writeSessionValue } from '../sessionStorage'
const props=defineProps<{order:any,owner:boolean}>(),data=ref<any>(null),busy=ref(false)
const session=useSession(),draft=ref<any[]|null>(null),reason=ref(''),draftRevision=ref(0)
const draftsKey=()=>`quoteDraft_${session.user?.id}_${props.order.id}`
const attempts=new Map<string,string>()
function keepDraft(){writeSessionValue(draftsKey(),draft.value?{items:draft.value,reason:reason.value,revision:draftRevision.value,keys:[...attempts]}:null)}
watch([draft,reason,draftRevision],keepDraft,{deep:true})
try{const saved=readSessionValue(draftsKey());if(saved){draft.value=saved.items;reason.value=saved.reason;draftRevision.value=saved.revision;for(const [s,k] of saved.keys||[])attempts.set(s,k)}}catch{}
async function discardDraft(){const r=await new Promise<UniApp.ShowModalRes>(resolve=>uni.showModal({title:'放弃编辑',content:'确定放弃尚未提交的报价修改？',success:resolve}));if(r.confirm){draft.value=null;keepDraft()}}
const canQuote=computed(()=>session.activeRole==='WORKER'&&props.order.workerId===session.user?.workerId&&props.order.facilityScope==='PRIVATE'&&['ASSIGNED','ACCEPTED','IN_PROGRESS'].includes(props.order.status))
function beginEdit(){const latest=data.value.quotes[0];draft.value=latest?lines(latest).map((i:any)=>({name:i.name,quantity:String(i.quantity),price:String(i.unitPriceFen/100)})):[{name:'人工费',quantity:'1',price:''},{name:'材料费',quantity:'1',price:''}];draftRevision.value=latest?.revision||0;reason.value=''}
async function adjustQuote(){
 if(!draft.value||busy.value)return
 const items=[] as any[]
 for(const i of draft.value){const quantity=Number(i.quantity),price=Number(i.price);if(!i.name.trim()||!String(i.quantity).trim()||!String(i.price).trim()||!Number.isInteger(quantity)||quantity<1||quantity>1000||!Number.isFinite(price)||price<0||Math.abs(price*100-Math.round(price*100))>0.00001){uni.showToast({title:'请完整填写明细，免收项目明确填0',icon:'none'});return}if(price>0)items.push({name:i.name,quantity,unitPriceFen:Math.round(price*100)})}
 const total=items.reduce((sum,i)=>sum+i.quantity*i.unitPriceFen,0),old=Number(data.value.acceptedQuote?.amountFen||data.value.quotes[0]?.amountFen||0)
 if(total<=0||total>10000000||!reason.value.trim()){uni.showToast({title:'总金额或调整原因无效',icon:'none'});return}
 const confirmed=await new Promise<UniApp.ShowModalRes>(resolve=>uni.showModal({title:'确认提交完整报价',content:`原价 ¥${(old/100).toFixed(2)} → 新价 ¥${(total/100).toFixed(2)}，变化 ¥${((total-old)/100).toFixed(2)}。居民需重新确认。`,success:resolve}))
 if(!confirmed.confirm)return
 if(await submit('quotes',{revision:draftRevision.value,items,reason:reason.value})){draft.value=null;reason.value=''}
}
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
    const signature=JSON.stringify([props.order.id,path,body]);if(!attempts.has(signature))attempts.set(signature,idempotencyKey());
    keepDraft()
    await request(`/repair/orders/${props.order.id}/billing/${path}`,'POST',body,attempts.get(signature)!)
    return true
  }catch{
    return false
    // The request helper displays the error; reload in case only the response was lost.
  }finally{
    try{await load()}catch{uni.showToast({title:'费用刷新失败，请手动刷新核对',icon:'none'})}finally{busy.value=false}
  }
}
function startPayment(){return submit('payment-attempts',{billId:data.value.bill.id})}
function confirmPayment(){return submit('mock-pay',{billId:data.value.bill.id,attemptNo:attempt.value.attemptNo})}
</script>
<template><view v-if="canQuote&&data" class="card"><button v-if="!draft" class="secondary" @click="beginEdit">调整完整报价</button><template v-else><view class="section-title">修改完整明细</view><view class="small">草稿已在本机保留，返回此工单可继续编辑。</view><view v-for="(line,i) in draft" :key="i"><input :disabled="busy" v-model="line.name" class="field" maxlength="100" placeholder="项目名称"/><input :disabled="busy" v-model="line.quantity" class="field" type="number" placeholder="数量"/><input :disabled="busy" v-model="line.price" class="field" type="digit" placeholder="单价（元，免收填0）"/></view><button :disabled="busy" v-if="draft.length<30" class="secondary" @click="draft.push({name:'',quantity:'1',price:''})">添加项目</button><input :disabled="busy" v-model="reason" class="field" maxlength="500" placeholder="调整原因"/><button class="primary" :disabled="busy" @click="adjustQuote">核对并提交</button><button class="secondary" :disabled="busy" @click="discardDraft">放弃编辑</button></template></view><view id="billing-panel" class="card"><view class="section-title">维修费用</view><view v-if="!order.billingVersion">历史工单，不补收费用。</view><view v-else-if="order.facilityScope==='PUBLIC'">公共设施 · 居民无需支付</view><template v-else-if="data"><view>先确认报价，维修验收后支付。</view><view v-if="data.acceptedQuote" class="notice">已确认金额 ¥{{money(data.acceptedQuote.amountFen)}}</view><view v-for="q in data.quotes" :key="q.id" class="spaced"><view>第{{q.revision}}版 · ¥{{money(q.amountFen)}} · {{names[q.status]}}</view><view v-for="(line,i) in lines(q)" :key="i" class="small">{{line.name}} × {{line.quantity}} · 单价 ¥{{money(line.unitPriceFen)}}</view><view class="muted">{{q.reason}}</view><template v-if="owner&&q.status==='PROPOSED'&&!['CANCELED','REJECTED','CLOSED'].includes(order.status)"><button class="primary" :disabled="busy" @click="submit('decision',{quoteId:q.id,accepted:true})">确认本版完整报价</button><button class="secondary" :disabled="busy" @click="submit('decision',{quoteId:q.id,accepted:false})">拒绝本版报价</button></template></view><view v-if="data.bill" class="notice">账单 ¥{{money(data.bill.amountFen)}} · {{data.bill.status==='MOCK_PAID'?'模拟支付完成，未真实扣款':'待支付'}}<view v-if="owner&&data.bill.status==='UNPAID'&&data.mockEnabled">
  <template v-if="remaining>0">
    <view>剩余支付时间 {{countdown}}</view>
    <button class="primary" :disabled="busy" @click="confirmPayment">确认模拟支付（不扣款）</button>
  </template>
  <template v-else>
    <view v-if="attempt">本次支付已超时</view>
    <button class="primary" :disabled="busy" @click="startPayment">{{attempt?'重新支付':'去支付'}}</button>
  </template>
</view></view><view v-if="!data.quotes.length" class="muted">等待物业报价。</view><button class="secondary" @click="load">刷新费用进度</button></template></view></template>
