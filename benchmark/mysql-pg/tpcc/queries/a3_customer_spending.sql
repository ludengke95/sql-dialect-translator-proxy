-- TPC-C A3: 客户消费排行
SELECT c_id, c_last, c_first, c_balance, c_ytd_payment
FROM customer
WHERE c_w_id = 1
ORDER BY c_ytd_payment DESC
LIMIT 10;