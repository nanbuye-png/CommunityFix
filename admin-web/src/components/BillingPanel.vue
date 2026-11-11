<script setup lang="ts">
import { ref,watch,onMounted,onUnmounted } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import { useSession } from '../stores/session'
import { get,post,key } from '../api'
const props=defineProps<{order:any}>(),emit=defineEmits(['updated'])
const data=ref<any>(null),busy=ref(false),reason=ref(''),scope=ref('PRIVATE'),location=ref(''),items=ref([{name:'人工费',quantity:1,unitPriceYuan:100}])
const labor=ref(0),material=ref(0),basis=ref('')
const auth=useSession(),editing=ref(false),quoteRevision=ref(0)
const savedKeys=new Map<string,string>()
const draftKey=()=>`cf_quote_${auth.user?.id}_${props.order.id}`
function persist(){if(editing.value)sessionStorage.setItem(draftKey(),JSON.stringify({items:items.value,reason:reason.value,revision:quoteRevision.value,keys:[...savedKeys]}))}
function restore(){editing.value=false;savedKeys.clear();try{const saved=JSON.parse(sessionStorage.getItem(draftKey())||'null');if(saved){items.value=saved.items;reason.value=saved.reason;quoteRevision.value=saved.revision;for(const [s,k] of saved.keys||[])savedKeys.set(s,k);editing.value=true}}catch{}}
function beginQuote(){const latest=data.value?.quotes[0];items.value=latest?lines(latest).map((i:any)=>({name:i.name,quantity:i.quantity,unitPriceYuan:i.unitPriceFen/100})):[{name:'人工费',quantity:1,unitPriceYuan:null},{name:'材料费',quantity:1,unitPriceYuan:null}] as any;quoteRevision.value=latest?.revision||0;reason.value='';editing.value=true;persist()}
async function discardQuote(){try{await ElMessageBox.confirm('放弃尚未提交的报价修改？','放弃编辑');editing.value=false;sessionStorage.removeItem(draftKey())}catch{}}
watch([items,reason],persist,{deep:true,flush:'sync'})
watch(()=>props.order.id,restore,{immediate:true,flush:'sync'})
async function sendQuote(){
 if(busy.value)return
 if(!reason.value.trim()||items.value.some(i=>!i.name.trim()||i.quantity==null||!Number.isInteger(i.quantity)||i.quantity<1||i.quantity>1000||i.unitPriceYuan==null||!Number.isFinite(i.unitPriceYuan)||i.unitPriceYuan<0||Math.abs(i.unitPriceYuan*100-Math.round(i.unitPriceYuan*100))>0.00001)){ElMessage.warning('请完整填写明细及原因，免收项目明确填0');return}
 const details=items.value.filter(i=>i.unitPriceYuan>0).map(i=>({name:i.name,quantity:i.quantity,unitPriceFen:Math.round(i.unitPriceYuan*100)})),total=details.reduce((s,i)=>s+i.quantity*i.unitPriceFen,0)
 if(total<=0||total>10000000){ElMessage.warning('总价须为0.01至100000元');return}
 try{await ElMessageBox.confirm(`原价 ¥${money(data.value.acceptedQuote?.amountFen||data.value.quotes[0]?.amountFen||0)} → 新价 ¥${money(total)}，变化 ¥${money(total-Number(data.value.acceptedQuote?.amountFen||data.value.quotes[0]?.amountFen||0))}。原因：${reason.value}。居民需重新确认。`,'核对完整报价')}catch{return}
 const body={revision:quoteRevision.value,items:details,reason:reason.value},signature=JSON.stringify(body)
 if(!savedKeys.has(signature))savedKeys.set(signature,key());persist();busy.value=true
 try{await post(`/repair/orders/${props.order.id}/billing/quotes`,body,savedKeys.get(signature)!);editing.value=false;sessionStorage.removeItem(draftKey());try{await load()}catch{ElMessage.warning('报价已保存，费用刷新失败，请刷新核对')}emit('updated')}catch{ElMessage.warning('未确认提交成功，草稿已保留；可重试或核对最新报价')}finally{busy.value=false}
}
const money=(n:any)=>(Number(n)/100).toFixed(2)
const names:Record<string,string>={PROPOSED:'待确认',ACCEPTED:'已确认',REJECTED:'已拒绝',SUPERSEDED:'已替换'}
function lines(q:any){try{return typeof q.itemsJson==='string'?JSON.parse(q.itemsJson):q.itemsJson}catch{return []}}
let requestVersion=0
async function load(){const version=++requestVersion;const result=await get(`/repair/orders/${props.order.id}/billing`);if(version===requestVersion){data.value=result;labor.value=Number(result.referencePrice?.laborFen||0)/100;material.value=Number(result.referencePrice?.materialFen||0)/100;basis.value=result.referencePrice?.basis||''}}
const liveRefresh=()=>{load().catch(()=>{})}
onMounted(()=>window.addEventListener('cf-notifications',liveRefresh))
onUnmounted(()=>{requestVersion++;window.removeEventListener('cf-notifications',liveRefresh)})
watch(()=>[props.order.id,props.order.version],()=>{scope.value=props.order.facilityScope;location.value=props.order.locationText||'';load()},{immediate:true})
async function submit(path:string,body:any){busy.value=true;try{await post(`/repair/orders/${props.order.id}/billing/${path}`,body,key());await load();emit('updated')}finally{busy.value=false}}
</script>
<template><section class="billing"><h3>维修费用</h3><p v-if="!order.billingVersion">历史工单，不补收费用。</p><template v-else><p>{{order.facilityScope==='PUBLIC'?'公共设施 · 居民无需支付':'自有设施 · 居民确认报价后维修，验收后支付'}}</p>
<template v-if="['SUBMITTED','PENDING_DISPATCH'].includes(order.status)"><el-select v-model="scope"><el-option value="PUBLIC" label="公共设施 · 免付"/><el-option value="PRIVATE" label="自有设施 · 收费"/></el-select><el-input v-model="location" placeholder="实际位置"/><el-input v-model="reason" placeholder="更正归属原因"/><el-button :loading="busy" @click="submit('scope',{version:order.version,facilityScope:scope,locationText:location,reason})">更正设施归属</el-button></template>
<template v-if="data"><p><RouterLink to="/reference-prices">维护本小区维修参考价</RouterLink></p><p v-if="data.acceptedQuote">已确认总价 ¥{{money(data.acceptedQuote.amountFen)}}</p><div v-for="q in data.quotes" :key="q.id"><strong>第{{q.revision}}版 · ¥{{money(q.amountFen)}} · {{names[q.status]}}</strong><div v-for="(line,i) in lines(q)" :key="i">{{line.name}} × {{line.quantity}} · 单价 ¥{{money(line.unitPriceFen)}}</div><p>{{q.reason}}</p></div>
<template v-if="order.facilityScope==='PRIVATE'&&['PENDING_DISPATCH','ASSIGNED','ACCEPTED','IN_PROGRESS'].includes(order.status)"><h4>完整报价（追加费用须包含原项目）</h4><el-button v-if="!editing" @click="beginQuote">编辑完整报价</el-button><template v-if="editing"><p>草稿在当前浏览器会话保留，重新进入可继续编辑。</p><div v-for="(line,i) in items" :key="i" class="line"><el-input :disabled="busy" v-model="line.name" placeholder="项目名称"/><el-input-number :disabled="busy" v-model="line.quantity" :min="1" :max="1000"/><el-input-number :disabled="busy" v-model="line.unitPriceYuan" :min="0" :max="100000" :precision="2"/><span>元/项</span><el-button :disabled="busy" @click="items.splice(i,1)">删除</el-button></div><el-button :disabled="busy||items.length>=30" @click="items.push({name:'材料费',quantity:1,unitPriceYuan:1})">添加明细</el-button><el-input v-model="reason" placeholder="报价或调整原因"/><el-button :loading="busy" @click="sendQuote">核对并发送报价</el-button><el-button :disabled="busy" @click="discardQuote">放弃编辑</el-button></template></template>
<p v-if="data.bill">账单 ¥{{money(data.bill.amountFen)}} · {{data.bill.status==='MOCK_PAID'?'模拟已支付（非真实到账）':'待支付'}}</p><p>当前仅模拟支付，不产生真实收款。</p><el-button @click="load">刷新费用进度</el-button></template></template></section></template>
<style scoped>.billing{padding:18px;background:#f4f8f7;border-radius:12px;margin:20px 0}.line{display:flex;flex-wrap:wrap;gap:8px;margin:8px 0}.billing .el-input{margin:8px 0}.line .el-input{width:150px}</style>
