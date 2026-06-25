import{e as ge,aV as ye,k as l,bl as ve,aZ as y,aR as Te,o as u,c as v,aU as s,g as a,w as n,a_ as q,a$ as Ce,a as T,h as B,b8 as C,b2 as p,u as i,c7 as we,aX as A,dP as He,aT as ke,E as h,cD as De,ds as Re,dt as Ee,du as xe,dQ as Ne,dR as Se,b as Be,bd as $e,d6 as Ue,bP as Fe,j as ze,d2 as Ve,bj as Pe,bJ as Le,da as je,f as qe,dS as Ae,_ as Ie}from"./index-B6YLXGex.js";import{_ as Me}from"./SearchWrap-DWkXh9qG.js";import{d as Oe,g as We}from"./hint-DiySYlfo.js";const Qe={class:"hint-box"},Ge={class:"tabs-container mb-20"},Je={class:"top-status-bar border-shadow mt-20"},Xe={class:"flex"},Ze=["onClick"],Ke=["onClick"],Ye={class:"dialog-file-body"},et={class:"el-upload__text"},tt={class:"mb-10"},at={class:"demo-content"},ot=ge({__name:"index",setup(nt){const{t}=ye(),w=ke(),H=l(!1),k=l(!1),$=l(`---
 name: WeatherQuery
 description: 一个天气查询技能，可以根据城市名称获取当前天气信息
 ---

 # 天气查询技能

 ## 功能描述
 这个技能可以根据用户提供的城市名称查询当前天气信息，包括温度、湿度、风速等详细数据。

 ## 使用方法
 用户可以通过以下方式调用此技能：
 - 直接询问"北京今天天气怎么样"
 - 提供"查询上海天气"
 - 询问"广州的天气情况"

 ## 参数说明
 - city: 城市名称（必需）
 - date: 查询日期（可选，默认为今天）

 ## 返回格式
 \`\`\`json
 {
   "city": "北京",
   "temperature": "25°C",
   "humidity": "60%",
   "wind_speed": "3.5m/s",
   "weather_condition": "晴朗",
   "update_time": "2024-03-03 10:00:00"
 }
 \`\`\`





 `),D=ve([]);let _=l([]),m=[];const U=l(!1),I=l(null),M=l(null),f=l("1"),O=y(()=>[{label:t("globalTab"),value:"1"},{label:t("personal"),value:"2"}]),W=y(()=>[{type:"input",label:"taskTarget",prop:"taskTarget",placeholder:"pleaseEnter"},{type:"input",label:"agentName",prop:"agentName",placeholder:"pleaseEnter"},{type:"input",label:"templateName",prop:"templateName",placeholder:"pleaseEnter"},{type:"select",label:"sourceType",prop:"sourceType",placeholder:"all",options:[{label:t("all"),value:"all"},{label:t("HumanAdded"),value:"1"},{label:t("nonmanual"),value:"2"}]}]),Q=y(()=>({1:t("HumanAdded"),2:t("AGENT"),3:t("import")})),G=y(()=>[{label:t("taskTarget"),prop:"taskTarget",width:"200"},{label:t("activationRules"),prop:"activationRules",width:"200"},{label:t("isPositive"),prop:"positive",width:"80"},{label:t("authority"),prop:"authority",width:"100"},{label:t("sourceType"),prop:"sourceType",width:"120",render:e=>Q.value[e.sourceType]},{label:t("solution"),prop:"solution",width:"300"},{label:t("createTime"),prop:"createTime",width:"150",formatter:e=>e.createTime.substring(0,10)}]),F=l([]),J=e=>{c(e)},X=()=>{c()},Z=()=>{w.push({name:"HintCreate",query:{title:"hintHistory>createHintHistory"}})},K=()=>{H.value=!0},Y=()=>{w.push({name:"HintTest",query:{title:"hintHistory>test"}})},ee=async e=>{let o=!1;for(let r=0;r<_.value.length;r++)if(_.value[r].name===e.name){o=!0;break}o||(_.value.push({name:e.name,url:URL.createObjectURL(e.raw),id:new Date().getTime()}),m.push(e.raw))},te=()=>{_.value.splice(0,1),D.splice(0,1),m.splice(0,1)},ae=()=>{h.warning(t("maxUploadOneFile"))},oe=e=>{Ae(e,o=>{o.code===200?(h.success(o.message),c()):h.error(o.message),U.value=!1,z()})},ne=()=>{if(!m||!m.length){h.warning(t("pleaseUploadFile"));return}U.value=!0;const e=new FormData;e.append("file",m[0]),oe(e)},z=()=>{H.value=!1,_.value=[],D.splice(0,1),m.splice(0,1)},le=()=>{k.value=!0},se=()=>{k.value=!1},ie=()=>{De($.value)},ce=e=>{w.push({name:"HintCreate",query:{id:e.id,title:"hintHistory>editHintHistory",type:"edit"}})},re=e=>{Re.confirm(t("deleteConfirmMessage"),"",{confirmButtonText:t("confirm"),cancelButtonText:t("cancel"),customClass:"del-message-box",type:"warning",center:!0,icon:Ee(xe)}).then(async()=>{const o=await Oe({},e.id);o.code===200&&(h.success(o.message),c())}).catch(()=>{})},R=l(1),E=l(10),V=l(0),c=async e=>{var b;console.log(e,"val");const o={current:R.value,size:E.value,taskTarget:(e==null?void 0:e.taskTarget)||"",agentName:(e==null?void 0:e.agentName)||"",authority:(e==null?void 0:e.authority)||"",templateName:(e==null?void 0:e.templateName)||"",scope:f.value||"",querySourceType:e?e.sourceType!=="all"?Number(e.sourceType):"":1},r=await We(o);r.code===200&&(F.value=r.data.data||[],V.value=((b=r.data)==null?void 0:b.totalCount)||0)},ue=e=>{E.value=e,c()},de=e=>{R.value=e,c()},pe=e=>{f.value=e,c()};return Te(()=>{c()}),(e,o)=>{const r=Ne,b=Se,me=Me,g=Be,x=$e,_e=Ue,P=Fe,L=ze,he=Ve,fe=Pe,be=Le,j=je,N=qe("permission");return u(),v(q,null,[s("div",Qe,[s("div",Ge,[a(b,{modelValue:f.value,"onUpdate:modelValue":o[0]||(o[0]=d=>f.value=d),onTabChange:pe},{default:n(()=>[(u(!0),v(q,null,Ce(O.value,(d,S)=>(u(),T(r,{key:S,label:d.label,name:d.value},null,8,["label","name"]))),128))]),_:1},8,["modelValue"])]),a(me,{ref_key:"searchWrapRef",ref:M,config:W.value,inline:!0,initialValues:{sourceType:"1"},onSubmit:J,onReset:X},null,8,["config"]),s("div",Je,[B((u(),T(_e,{btnText:i(t)("createHintHistory"),hideSearch:!0,onHandleCreate:Z,onSearchFn:c,placeholder:e.$t("searchByName")},{default:n(()=>[a(x,{type:"primary",onClick:K,plain:"",class:"ml-10"},{default:n(()=>[a(g,{"icon-class":"import",color:"var(--svg-icon-color)"}),C("   "+p(e.$t("import")),1)]),_:1}),a(x,{type:"primary",onClick:Y,plain:"",class:"ml-10"},{default:n(()=>[a(g,{"icon-class":"play",color:"var(--svg-icon-color)"}),C("   "+p(e.$t("test")),1)]),_:1})]),_:1},8,["btnText","placeholder"])),[[N,"/agent-hint-history/add"]]),a(he,{tableData:F.value,tableHeader:G.value,onHandleSizeChange:ue,onHandleCurrentChange:de,totalCount:V.value,pageSize:E.value,currentPage:R.value,hasPagination:!1,class:"tool-table"},{default:n(()=>[a(P,{prop:"creator",label:i(t)("createBy"),width:"150"},null,8,["label"]),a(P,{fixed:"right",label:i(t)("operation"),width:"120"},{default:n(({row:d})=>[s("div",Xe,[a(L,{content:e.$t("edit"),placement:"top",manual:!0,"show-after":500,"hide-after":0},{default:n(()=>[B((u(),v("div",{class:"pointer",onClick:S=>ce(d)},[a(g,{"icon-class":"table-edit",size:"26",color:"var(--svg-icon-color)"})],8,Ze)),[[N,"/agent-hint-history/edit"]])]),_:2},1032,["content"]),a(L,{content:e.$t("delete"),placement:"top",manual:!0,"show-after":500,"hide-after":0},{default:n(()=>[B((u(),v("div",{class:"ml-10 pointer",onClick:S=>re(d)},[a(g,{"icon-class":"table-delete",size:"26",color:"#98a2b3"})],8,Ke)),[[N,"/agent-hint-history/delete"]])]),_:2},1032,["content"])])]),_:1},8,["label"])]),_:1},8,["tableData","tableHeader","totalCount","pageSize","currentPage"])])]),H.value?(u(),T(j,{key:0,title:i(t)("import"),btnText:i(t)("confirmButton"),class:"Dialog",onClose:z,onSureBtn:ne},{default:n(()=>[s("div",Ye,[a(x,{type:"primary",onClick:le,class:"mb-20"},{default:n(()=>[C(p(i(t)("viewDemo")),1)]),_:1}),a(be,{ref_key:"uploadRef",ref:I,class:"upload-wrap","file-list":D,drag:"",accept:".zip,.md",action:"#",multiple:"",limit:10,"auto-upload":!1,"on-change":ee,"on-remove":te,"on-exceed":ae},{default:n(()=>[a(fe,{class:"el-icon--upload"},{default:n(()=>[a(i(we))]),_:1}),s("div",et,[s("div",tt,[C(p(e.$t("dragFileHereOrClickToUpload"))+" ",1),s("em",null,p(e.$t("clickUpload")),1)]),s("div",null,p(i(t)("hintUploadTips")),1)])]),_:1},8,["file-list"])])]),_:1},8,["title","btnText"])):A("",!0),k.value?(u(),T(j,{key:1,title:i(t)("demo"),disableConfirm:!0,icon:i(He),class:"Dialog",onClose:se,onCopyBtn:ie},{default:n(()=>[s("div",at,[s("pre",null,p($.value),1)])]),_:1},8,["title","icon"])):A("",!0)],64)}}}),ct=Ie(ot,[["__scopeId","data-v-53e06565"]]);export{ct as default};
