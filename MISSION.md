# Mission: Production VLM Review Chain

## Why
理解并能安全运营本项目的绘图链路：让 VLM 审查真实渲染结果，同时不把画布修改权直接交给 VLM，也不破坏现有持久化、版本控制和故障降级。

## Success looks like
- 能沿着一次请求定位绘图、截图、审查、修复、验证和遥测代码
- 能解释 `drawio_done`、review decision 与最终链路完成之间的区别
- 能安全执行数据库迁移，并按 shadow → visible → auto-repair 顺序上线

## Constraints
- 以当前仓库实现为事实来源
- 优先保证已有画布和用户数据安全
- 自动修复必须有严格边界、最多一次且可观测

## Out of scope
- 训练或微调 VLM
- 重做绘图 Agent 的通用推理框架
- 自动化生产数据库发布系统
