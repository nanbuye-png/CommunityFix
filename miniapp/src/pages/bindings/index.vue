<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request } from '../../api'
import { useSession } from '../../stores/session'
const rows=ref<any[]>([]),communities=ref<any[]>([]),buildings=ref<any[]>([]),rooms=ref<any[]>([]),ci=ref(-1),bi=ref(-1),ri=ref(-1),busy=ref(false),showForm=ref(false)
const labels:Record<string,string>={PENDING:'待审核',APPROVED:'已通过',REJECTED:'已驳回',REVOKED:'已撤销'}
async function load(){rows.value=(await request('/room-bindings')).records;communities.value=await request('/communities')}
onShow(()=>{if(useSession().requireLogin('RESIDENT'))load()})
async function community(event:any){ci.value=Number(event.detail.value);bi.value=-1;ri.value=-1;buildings.value=await request('/buildings','GET',{communityId:communities.value[ci.value].id});rooms.value=[]}
async function building(event:any){bi.value=Number(event.detail.value);ri.value=-1;rooms.value=(await request('/rooms','GET',{buildingId:buildings.value[bi.value].id})).map((r:any)=>({...r,label:`${r.unitNo}单元 ${r.roomNo}室`}))}
async function submit(){if(ri.value<0){uni.showToast({title:'请选择房屋',icon:'none'});return}busy.value=true;try{await request('/room-bindings','POST',{roomId:rooms.value[ri.value].id});uni.showToast({title:'申请已提交',icon:'success'});showForm.value=false;await load()}finally{busy.value=false}}
</script>
<template><view class="page"><view class="eyebrow">我的居住信息</view><view class="page-title">房屋绑定</view><view class="muted">物业审核通过后，就可以为这套房屋报修。</view><view class="spaced"><view v-for="binding in rows" :key="binding.id" class="card"><view class="row"><text class="order-title">{{binding.communityName}}</text><text class="pill">{{labels[binding.status]}}</text></view><view class="muted">{{binding.buildingNo}}栋 {{binding.unitNo}}单元 {{binding.roomNo}}室</view><view v-if="binding.reviewReason" class="notice">{{binding.reviewReason}}</view></view></view><view v-if="!rows.length" class="empty">还没有绑定房屋<br>选择你的房屋并提交申请</view><button class="primary" @click="showForm=!showForm">{{showForm?'收起申请':'申请绑定房屋'}}</button><view v-if="showForm" class="card spaced"><view class="label">小区</view><picker :range="communities" range-key="name" @change="community"><view class="field">{{ci>=0?communities[ci].name:'请选择小区'}}</view></picker><view class="label">楼栋</view><picker :range="buildings" range-key="buildingNo" @change="building"><view class="field">{{bi>=0?buildings[bi].buildingNo+'栋':'请选择楼栋'}}</view></picker><view class="label">房屋</view><picker :range="rooms" range-key="label" @change="ri=Number($event.detail.value)"><view class="field">{{ri>=0?rooms[ri].label:'请选择单元与房号'}}</view></picker><button class="primary" :loading="busy" @click="submit">提交绑定申请</button></view></view></template>
