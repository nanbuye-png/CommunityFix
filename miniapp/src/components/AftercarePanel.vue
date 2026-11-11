<script setup lang="ts">
import { ref,watch } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request,idempotencyKey,date } from '../api'
const props=defineProps<{order:any}>()
const rows=ref<any[]>([]),content=ref(''),busy=ref(false),error=ref('')
const names:Record<string,string>={REQUESTED:'待受理',PROCESSING:'处理中',PENDING_CONFIRM:'待你确认',CLOSED:'已完成',REJECTED:'未受理'}
let attempt:{signature:string,key:string}|null=null
async function load(){try{rows.value=await request(`/repair/orders/${props.order.id}/aftercare`);error.value=''}catch{error.value='售后记录加载失败，请刷新重试'}}
watch(()=>props.order.id,load,{immediate:true});onShow(load)
async function submit(item?:any,action?:string){
 if(busy.value)return
 const message=action==='confirm'?'居民确认售后问题已解决':content.value.trim()
 if(!message){error.value='请描述问题或继续反馈的原因';return}
 const path=`/repair/orders/${props.order.id}/aftercare`+(item?`/${item.id}`:'')
 const body=item?{version:item.version,action,content:message}:{content:message}
 const signature=path+JSON.stringify(body)
 if(attempt?.signature!==signature)attempt={signature,key:idempotencyKey()}
 busy.value=true
 try{await request(path,'POST',body,attempt.key);attempt=null;content.value='';await load()}catch(e:any){error.value=e.message||'提交失败，内容已保留，可重试';if(e.status===409)await load()}finally{busy.value=false}
}
</script>
<template><view class="card"><view class="section-title">售后服务</view><view class="small muted">验收后问题再次出现，可申请售后。是否收费由物业核实，不会自动退款或修改原账单。</view><view v-if="error" class="error">{{error}}</view><view v-for="item in rows" :key="item.id" class="record"><view>{{names[item.status]}} · {{date(item.createdAt)}}</view><view>{{item.content}}</view><view v-for="(log,i) in item.logs" :key="i" class="small">{{date(log.createdAt)}} · {{names[log.status]}}：{{log.content}}</view><template v-if="item.status==='PENDING_CONFIRM'"><button :disabled="busy" class="primary" @click="submit(item,'confirm')">确认问题已解决</button><button :disabled="busy||!content.trim()" class="secondary" @click="submit(item,'reopen')">仍未解决，提交下方反馈</button></template></view><textarea v-model="content" maxlength="1000" class="field textarea" placeholder="描述故障或继续反馈的问题"/><button v-if="!rows.some(r=>['REQUESTED','PROCESSING','PENDING_CONFIRM'].includes(r.status))" class="primary" :loading="busy" :disabled="busy||!content.trim()" @click="submit()">申请售后</button><button class="secondary" :disabled="busy" @click="load">刷新售后进度</button></view></template>
<style scoped>.record{padding:20rpx 0;border-bottom:1px solid #dbe5e5;white-space:pre-wrap;line-height:1.8}</style>
