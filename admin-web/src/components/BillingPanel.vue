<script setup lang="ts">
import { ref,watch,onMounted,onUnmounted } from 'vue'
import { get,post,key } from '../api'
const props=defineProps<{order:any}>(),emit=defineEmits(['updated'])
const data=ref<any>(null),busy=ref(false),reason=ref(''),scope=ref('PRIVATE'),location=ref(''),items=ref([{name:'人工费',quantity:1,unitPriceYuan:100}])
const money=(n:any)=>(Number(n)/100).toFixed(2)
const names:Record<string,string>={PROPOSED:'待确认',ACCEPTED:'已确认',REJECTED:'已拒绝',SUPERSEDED:'已替换'}
function lines(q:any){try{return typeof q.itemsJson==='string'?JSON.parse(q.itemsJson):q.itemsJson}catch{return []}}
let requestVersion=0
async function load(){const version=++requestVersion;const result=await get(`/repair/orders/${props.order.id}/billing`);if(version===requestVersion)data.value=result}
const liveRefresh=()=>{load().catch(()=>{})}
onMounted(()=>window.addEventListener('cf-notifications',liveRefresh))
onUnmounted(()=>{requestVersion++;window.removeEventListener('cf-notifications',liveRefresh)})
watch(()=>[props.order.id,props.order.version],()=>{scope.value=props.order.facilityScope;location.value=props.order.locationText||'';load()},{immediate:true})
async function submit(path:string,body:any){busy.value=true;try{await post(`/repair/orders/${props.order.id}/billing/${path}`,body,key());await load();emit('updated')}finally{busy.value=false}}
</script>
<template><section class="billing"><h3>维修费用</h3><p v-if="!order.billingVersion">历史工单，不补收费用。</p><template v-else><p>{{order.facilityScope==='PUBLIC'?'公共设施 · 居民无需支付':'自有设施 · 居民确认报价后维修，验收后支付'}}</p>
<template v-if="order.status==='SUBMITTED'"><el-select v-model="scope"><el-option value="PUBLIC" label="公共设施 · 免付"/><el-option value="PRIVATE" label="自有设施 · 收费"/></el-select><el-input v-model="location" placeholder="实际位置"/><el-input v-model="reason" placeholder="更正归属原因"/><el-button :loading="busy" @click="submit('scope',{facilityScope:scope,locationText:location,reason})">更正设施归属</el-button></template>
<template v-if="data"><p v-if="data.acceptedQuote">已确认总价 ¥{{money(data.acceptedQuote.amountFen)}}</p><div v-for="q in data.quotes" :key="q.id"><strong>第{{q.revision}}版 · ¥{{money(q.amountFen)}} · {{names[q.status]}}</strong><div v-for="(line,i) in lines(q)" :key="i">{{line.name}} × {{line.quantity}} · 单价 ¥{{money(line.unitPriceFen)}}</div><p>{{q.reason}}</p></div>
<template v-if="order.facilityScope==='PRIVATE'&&['PENDING_DISPATCH','ASSIGNED','ACCEPTED','IN_PROGRESS'].includes(order.status)"><h4>完整报价（追加费用须包含原项目）</h4><div v-for="(line,i) in items" :key="i" class="line"><el-input v-model="line.name" placeholder="项目名称"/><el-input-number v-model="line.quantity" :min="1" :max="1000"/><el-input-number v-model="line.unitPriceYuan" :min="0.01" :max="100000" :precision="2"/><span>元/项</span><el-button @click="items.splice(i,1)">删除</el-button></div><el-button :disabled="items.length>=30" @click="items.push({name:'材料费',quantity:1,unitPriceYuan:1})">添加明细</el-button><el-input v-model="reason" placeholder="报价或调整原因"/><el-button :loading="busy" @click="submit('quotes',{revision:data.quotes[0]?.revision||0,items:items.map(i=>({name:i.name,quantity:i.quantity,unitPriceFen:Math.round(i.unitPriceYuan*100)})),reason})">发送报价</el-button></template>
<p v-if="data.bill">账单 ¥{{money(data.bill.amountFen)}} · {{data.bill.status==='MOCK_PAID'?'模拟已支付（非真实到账）':'待支付'}}</p><p>当前仅模拟支付，不产生真实收款。</p><el-button @click="load">刷新费用进度</el-button></template></template></section></template>
<style scoped>.billing{padding:18px;background:#f4f8f7;border-radius:12px;margin:20px 0}.line{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}.billing .el-input{margin:8px 0}.line .el-input{width:150px}</style>
