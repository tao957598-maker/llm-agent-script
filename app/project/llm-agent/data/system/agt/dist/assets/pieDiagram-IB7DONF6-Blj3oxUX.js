import{p as U}from"./chunk-4BMEZGHF-Bcm3JhT6.js";import{ah as S,a9 as z,gb as j,$ as H,K,L as Z,s as q,t as J,x as Q,v as X,p,G,R as Y,y as tt,a0 as et,a4 as at,ap as rt,H as nt}from"./index-DHIO0Zt0.js";import{p as it}from"./radar-MK3ICKWK-GDo4xY_i.js";import{d as N}from"./arc-jCUZX50A.js";import{o as st}from"./ordinal-Cboi1Yqb.js";import"./reduce-BTc7pP7t.js";import"./min-DXoGcIRp.js";import"./init-Gi6I4Gst.js";function ot(t,a){return a<t?-1:a>t?1:a>=t?0:NaN}function lt(t){return t}function ct(){var t=lt,a=ot,h=null,o=S(0),g=S(z),x=S(0);function i(e){var r,l=(e=j(e)).length,c,A,m=0,u=new Array(l),n=new Array(l),v=+o.apply(this,arguments),w=Math.min(z,Math.max(-z,g.apply(this,arguments)-v)),f,$=Math.min(Math.abs(w)/l,x.apply(this,arguments)),T=$*(w<0?-1:1),d;for(r=0;r<l;++r)(d=n[u[r]=r]=+t(e[r],r,e))>0&&(m+=d);for(a!=null?u.sort(function(y,C){return a(n[y],n[C])}):h!=null&&u.sort(function(y,C){return h(e[y],e[C])}),r=0,A=m?(w-l*T)/m:0;r<l;++r,v=f)c=u[r],d=n[c],f=v+(d>0?d*A:0)+T,n[c]={data:e[c],index:r,value:d,startAngle:v,endAngle:f,padAngle:$};return n}return i.value=function(e){return arguments.length?(t=typeof e=="function"?e:S(+e),i):t},i.sortValues=function(e){return arguments.length?(a=e,h=null,i):a},i.sort=function(e){return arguments.length?(h=e,a=null,i):h},i.startAngle=function(e){return arguments.length?(o=typeof e=="function"?e:S(+e),i):o},i.endAngle=function(e){return arguments.length?(g=typeof e=="function"?e:S(+e),i):g},i.padAngle=function(e){return arguments.length?(x=typeof e=="function"?e:S(+e),i):x},i}var O=H.pie,F={sections:new Map,showData:!1,config:O},b=F.sections,R=F.showData,ut=structuredClone(O),pt=p(()=>structuredClone(ut),"getConfig"),gt=p(()=>{b=new Map,R=F.showData,Y()},"clear"),dt=p(({label:t,value:a})=>{b.has(t)||(b.set(t,a),G.debug(`added new section: ${t}, with value: ${a}`))},"addSection"),ft=p(()=>b,"getSections"),ht=p(t=>{R=t},"setShowData"),mt=p(()=>R,"getShowData"),P={getConfig:pt,clear:gt,setDiagramTitle:K,getDiagramTitle:Z,setAccTitle:q,getAccTitle:J,setAccDescription:Q,getAccDescription:X,addSection:dt,getSections:ft,setShowData:ht,getShowData:mt},vt=p((t,a)=>{U(t,a),a.setShowData(t.showData),t.sections.map(a.addSection)},"populateDb"),yt={parse:p(async t=>{const a=await it("pie",t);G.debug(a),vt(a,P)},"parse")},St=p(t=>`
  .pieCircle{
    stroke: ${t.pieStrokeColor};
    stroke-width : ${t.pieStrokeWidth};
    opacity : ${t.pieOpacity};
  }
  .pieOuterCircle{
    stroke: ${t.pieOuterStrokeColor};
    stroke-width: ${t.pieOuterStrokeWidth};
    fill: none;
  }
  .pieTitleText {
    text-anchor: middle;
    font-size: ${t.pieTitleTextSize};
    fill: ${t.pieTitleTextColor};
    font-family: ${t.fontFamily};
  }
  .slice {
    font-family: ${t.fontFamily};
    fill: ${t.pieSectionTextColor};
    font-size:${t.pieSectionTextSize};
    // fill: white;
  }
  .legend text {
    fill: ${t.pieLegendTextColor};
    font-family: ${t.fontFamily};
    font-size: ${t.pieLegendTextSize};
  }
`,"getStyles"),xt=St,At=p(t=>{const a=[...t.entries()].map(o=>({label:o[0],value:o[1]})).sort((o,g)=>g.value-o.value);return ct().value(o=>o.value)(a)},"createPieArcs"),wt=p((t,a,h,o)=>{G.debug(`rendering pie chart
`+t);const g=o.db,x=tt(),i=et(g.getConfig(),x.pie),e=40,r=18,l=4,c=450,A=c,m=at(a),u=m.append("g");u.attr("transform","translate("+A/2+","+c/2+")");const{themeVariables:n}=x;let[v]=rt(n.pieOuterStrokeWidth);v??(v=2);const w=i.textPosition,f=Math.min(A,c)/2-e,$=N().innerRadius(0).outerRadius(f),T=N().innerRadius(f*w).outerRadius(f*w);u.append("circle").attr("cx",0).attr("cy",0).attr("r",f+v/2).attr("class","pieOuterCircle");const d=g.getSections(),y=At(d),C=[n.pie1,n.pie2,n.pie3,n.pie4,n.pie5,n.pie6,n.pie7,n.pie8,n.pie9,n.pie10,n.pie11,n.pie12],D=st(C);u.selectAll("mySlices").data(y).enter().append("path").attr("d",$).attr("fill",s=>D(s.data.label)).attr("class","pieCircle");let W=0;d.forEach(s=>{W+=s}),u.selectAll("mySlices").data(y).enter().append("text").text(s=>(s.data.value/W*100).toFixed(0)+"%").attr("transform",s=>"translate("+T.centroid(s)+")").style("text-anchor","middle").attr("class","slice"),u.append("text").text(g.getDiagramTitle()).attr("x",0).attr("y",-(c-50)/2).attr("class","pieTitleText");const M=u.selectAll(".legend").data(D.domain()).enter().append("g").attr("class","legend").attr("transform",(s,k)=>{const E=r+l,_=E*D.domain().length/2,B=12*r,V=k*E-_;return"translate("+B+","+V+")"});M.append("rect").attr("width",r).attr("height",r).style("fill",D).style("stroke",D),M.data(y).append("text").attr("x",r+l).attr("y",r-l).text(s=>{const{label:k,value:E}=s.data;return g.getShowData()?`${k} [${E}]`:k});const I=Math.max(...M.selectAll("text").nodes().map(s=>(s==null?void 0:s.getBoundingClientRect().width)??0)),L=A+e+r+l+I;m.attr("viewBox",`0 0 ${L} ${c}`),nt(m,c,L,i.useMaxWidth)},"draw"),Ct={draw:wt},Gt={parser:yt,db:P,renderer:Ct,styles:xt};export{Gt as diagram};
