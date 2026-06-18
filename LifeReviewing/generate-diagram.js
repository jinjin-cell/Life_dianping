
const plantumlEncoder = require('plantuml-encoder');

const plantUmlCode = `
@startuml
actor A
participant B
database C

activate A
A -&gt; B: 创建本地数据接口对象
activate B
A --&gt; A: 休眠等待
B --&gt; A: 创建成功消息
deactivate B
A -&gt; B: 连接数据库服务器的IP和口令
activate B
B -&gt; C: 创建数据库服务对象
activate C
C --&gt; B: 创建成功消息
deactivate C
B --&gt; A: 数据库服务就绪消息
deactivate B

A -&gt; B: SQL查询命令
activate B
B -&gt; C: SQL查询命令
activate C
C --&gt; B: SQL查询结果
deactivate C
B --&gt; A: SQL查询结果
deactivate B

A -&gt; B: 销毁消息
activate B
B -&gt; C: 销毁消息
activate C
destroy C
deactivate C
destroy B
deactivate B
deactivate A

@enduml
`;

const encoded = plantumlEncoder.encode(plantUmlCode);
const imgUrl = `https://www.plantuml.com/plantuml/png/${encoded}`;
console.log('顺序图图片链接:');
console.log(imgUrl);
console.log('\n你也可以直接在浏览器中打开以下链接查看SVG版本:');
console.log(`https://www.plantuml.com/plantuml/svg/${encoded}`);
