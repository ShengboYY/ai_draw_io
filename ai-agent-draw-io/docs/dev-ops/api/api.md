接口；query_ai_agent_config_list
用途；查询智能体配置列表，可以查询到配置的智能体

接口；create_session
用途；创建会话ID，每次新的对话，要创建一个新的Session
说明；登录用户使用服务端 session；匿名用户先调用 POST /anonymous-workspaces 获取 HttpOnly
credential cookie。ownerId、query/body userId 以及旧 X-Workspace-Id header 都不是权限凭证。

接口；chat
用途；agent 对话，需要传入必要的参数。
