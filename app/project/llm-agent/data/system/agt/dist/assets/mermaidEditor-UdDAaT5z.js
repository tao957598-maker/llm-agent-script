import{_ as d,k as o,c as p,aU as t,g as s,u as a,bp as m,a_ as _,b5 as I,bK as f,bL as P,o as R}from"./index-CS6_AVAi.js";const h={class:"mermaid-editor flex",style:{"overflow-y":"auto"}},y={class:""},D={__name:"mermaidEditor",setup(F){const r=o([{design_element:"UI场景(视觉)",test_content:["验证搜索界面是否清晰显示OKP Group名称输入框","验证搜索结果列表是否明确展示匹配的CDD案例"]},{design_element:"栏位和按钮",test_content:["Validate Group ID field input constraints (length, format)"]}]),e=o(`sequenceDiagram
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
`);return(v,n)=>{const c=I,i=f,u=P;return R(),p(_,null,[t("div",h,[s(c,{type:"textarea",modelValue:a(e),"onUpdate:modelValue":n[0]||(n[0]=l=>m(e)?e.value=l:null),style:{width:"500px"},rows:20,placeholder:"输入 Mermaid 图表代码..."},null,8,["modelValue"]),t("div",y,[s(i,{chart:a(e)},null,8,["chart"])])]),t("div",null,[s(u,{jsonData:a(r)},null,8,["jsonData"])])],64)}}},O=d(D,[["__scopeId","data-v-0cee5033"]]);export{O as default};
