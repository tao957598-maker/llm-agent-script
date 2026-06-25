import{_ as m}from"./VueJson-DxGBl2Mt.js";import{_ as p}from"./MermaidChart-DoKkGs2w.js";import{_ as d,j as r,c as _,g as t,k as s,u as o,m as I,F as f,q as P,o as R}from"./index-BsWbMS9S.js";import"./mermaid.core-a384FvSQ.js";const F={class:"mermaid-editor flex",style:{"overflow-y":"auto"}},h={class:""},y={__name:"mermaidEditor",setup(D){const n=r([{design_element:"UI场景(视觉)",test_content:["验证搜索界面是否清晰显示OKP Group名称输入框","验证搜索结果列表是否明确展示匹配的CDD案例"]},{design_element:"栏位和按钮",test_content:["Validate Group ID field input constraints (length, format)"]}]),e=r(`sequenceDiagram
  participant Customer
  participant Issuer/OFI
  participant RPP
  participant Acquirer/RFI
  participant Merch 
  Customer->>Issuer/OFI: (1) Customer scans QR code
  Issuer/OFI->>RPP: (2) Issuer sends Account Enquiry request
  RPP->>Issuer/OFI: (3) RPP sends Account Enquiry response
  Issuer/OFI->>Customer: (4) Display Error information
  note over Issuer/OFI, RPP: Rejected by RPP
`);return(v,a)=>{const c=P,i=p,u=m;return R(),_(f,null,[t("div",F,[s(c,{type:"textarea",modelValue:o(e),"onUpdate:modelValue":a[0]||(a[0]=l=>I(e)?e.value=l:null),style:{width:"500px"},rows:20,placeholder:"输入 Mermaid 图表代码..."},null,8,["modelValue"]),t("div",h,[s(i,{chart:o(e)},null,8,["chart"])])]),t("div",null,[s(u,{jsonData:o(n)},null,8,["jsonData"])])],64)}}},q=d(y,[["__scopeId","data-v-0cee5033"]]);export{q as default};
