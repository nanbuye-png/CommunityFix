<script setup lang="ts">
import { ref,watch } from 'vue'
import { get,post,key } from '../api'
const props=defineProps<{order:any}>()
const rows=ref<any[]>([]),content=ref(''),busy=ref(false),error=ref('')
const names:Record<string,string>={REQUESTED:'待受理',PROCESSING:'处理中',PENDING_CONFIRM:'待居民确认',CLOSED:'已完成',REJECTED:'未受理'}
let attempt:{signature:string,key:string}|null=null
async function load(){try{rows.value=await get(`/repair/orders/${props.order.id}/aftercare`);error.value=''}catch{error.value='售后记录加载失败，请重试'}}
watch(()=>props.order.id,load,{immediate:true})
async function submit(item:any,action:string){
 if(busy.value||!content.value.trim())return
 const body={version:item.version,action,content:content.value.trim()},path=`/repair/orders/${props.order.id}/aftercare/${item.id}`
 const signature=path+JSON.stringify(body)
 if(attempt?.signature!==signature)attempt={signature,key:key()}
 busy.value=true
 try{await post(path,body,attempt.key);attempt=null;content.value='';await load()}catch{error.value='提交失败，请刷新确认当前进度后重试'}finally{busy.value=false}
}
</script>
<template><section class="aftercare"><h3>售后处理</h3><p>售后不改变原工单、账单或支付状态；请如实填写核实情况与处理结果。</p><p v-if="error">{{error}}</p><p v-if="!rows.length">暂无售后申请</p><article v-for="item in rows" :key="item.id"><h4>{{names[item.status]}}</h4><p>{{item.content}}</p><p v-for="(log,i) in item.logs" :key="i">{{names[log.status]}}：{{log.content}}</p><template v-if="['REQUESTED','PROCESSING'].includes(item.status)"><el-input v-model="content" type="textarea" maxlength="1000" placeholder="受理说明、拒绝原因或实际处理结果"/><el-button v-if="item.status==='REQUESTED'" :disabled="busy||!content.trim()" @click="submit(item,'accept')">受理</el-button><el-button v-if="item.status==='REQUESTED'" :disabled="busy||!content.trim()" @click="submit(item,'reject')">说明原因并拒绝</el-button><el-button v-if="item.status==='PROCESSING'" :disabled="busy||!content.trim()" @click="submit(item,'resolve')">提交处理结果，等待居民确认</el-button></template></article><el-button :disabled="busy" @click="load">刷新售后进度</el-button></section></template>
<style scoped>.aftercare{padding:18px;background:#f4f8f7;border-radius:12px;margin:20px 0}article{white-space:pre-wrap;border-bottom:1px solid #ddd;padding-bottom:16px}</style>
