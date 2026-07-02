/**
 * 外部接口适配器层；当需要调用外部接口时，则创建出这一层，并定义接口，之后由基础设施层的 adapter 层具体实现。
 * 账户上下文的对外依赖（如邮件发送）接口定义在此。
 */
package org.zipp.ai.domain.account.adapter.port;
