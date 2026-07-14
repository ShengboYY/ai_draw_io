ALTER TABLE agent_llm_call
    ADD COLUMN pricing_version VARCHAR(64) NULL COMMENT '记录调用时采用的价格表版本' AFTER total_tokens,
    ADD COLUMN input_price_per_million_usd DECIMAL(12,6) NULL COMMENT '调用时输入 token 单价快照' AFTER pricing_version,
    ADD COLUMN output_price_per_million_usd DECIMAL(12,6) NULL COMMENT '调用时输出 token 单价快照' AFTER input_price_per_million_usd,
    ADD COLUMN estimated_cost_usd DECIMAL(18,9) NULL COMMENT '按调用时价格快照估算的成本' AFTER output_price_per_million_usd;
