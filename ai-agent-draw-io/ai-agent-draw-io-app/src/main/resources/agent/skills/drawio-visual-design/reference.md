# Draw.io House Style Reference

## Golden Example [P0]
Canonical output shape - a request flow with boundary, numbered edges, distinct tracks for the request/return pair, and a legend:

```xml
<mxCell id="2" value="AI Diagram System" style="text;html=1;strokeColor=none;fillColor=none;align=center;fontSize=18;fontStyle=1;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="340" y="20" width="380" height="30" as="geometry"/></mxCell>
<mxCell id="3" value="AI Diagram Service" style="rounded=0;whiteSpace=wrap;html=1;fillColor=none;dashed=1;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;" vertex="1" parent="1"><mxGeometry x="300" y="70" width="660" height="480" as="geometry"/></mxCell>
<mxCell id="4" value="Web Frontend" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="60" y="230" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="5" value="Backend API&lt;br&gt;&lt;font style=&quot;font-size:10px&quot;&gt;auth / routing&lt;/font&gt;" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="380" y="230" width="160" height="70" as="geometry"/></mxCell>
<mxCell id="6" value="AI Service&lt;br&gt;&lt;font style=&quot;font-size:10px&quot;&gt;generates draw.io XML&lt;/font&gt;" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=12;" vertex="1" parent="1"><mxGeometry x="700" y="120" width="180" height="70" as="geometry"/></mxCell>
<mxCell id="7" value="User History DB" style="shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;backgroundOutline=1;size=15;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=12;" vertex="1" parent="1"><mxGeometry x="720" y="380" width="140" height="80" as="geometry"/></mxCell>
<mxCell id="8" value="1. draw request" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.3;entryX=0;entryY=0.3;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="9" value="2. call model" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.3;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="10" value="3. return XML" style="endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0;exitY=0.7;entryX=1;entryY=0.6;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="6" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="11" value="4. save history" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.7;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="12" value="5. render diagram" style="endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0;exitY=0.7;entryX=1;entryY=0.7;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="13" value="" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;" vertex="1" parent="1"><mxGeometry x="60" y="420" width="200" height="90" as="geometry"/></mxCell>
<mxCell id="14" value="Legend" style="text;html=1;strokeColor=none;fillColor=none;fontSize=11;fontStyle=1;align=left;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="72" y="428" width="80" height="18" as="geometry"/></mxCell>
<mxCell id="15" value="" style="endArrow=classic;html=1;" edge="1" parent="1"><mxGeometry relative="1" as="geometry"><mxPoint x="75" y="470" as="sourcePoint"/><mxPoint x="120" y="470" as="targetPoint"/></mxGeometry></mxCell>
<mxCell id="16" value="request / write" style="text;html=1;strokeColor=none;fillColor=none;fontSize=10;align=left;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="128" y="460" width="120" height="18" as="geometry"/></mxCell>
<mxCell id="17" value="" style="endArrow=classic;html=1;dashed=1;" edge="1" parent="1"><mxGeometry relative="1" as="geometry"><mxPoint x="75" y="495" as="sourcePoint"/><mxPoint x="120" y="495" as="targetPoint"/></mxGeometry></mxCell>
<mxCell id="18" value="return / async" style="text;html=1;strokeColor=none;fillColor=none;fontSize=10;align=left;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="128" y="485" width="120" height="18" as="geometry"/></mxCell>
```

Read from the example: numbered solid request path, dashed returns on separate tracks, boundary behind content, storage cylinder, point-anchored legend lines, and clear routing channels.
