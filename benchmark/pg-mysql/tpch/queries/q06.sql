-- TPC-H Q6: 预测收入变化
SELECT
    SUM(l_extendedprice * l_discount) AS revenue
FROM
    lineitem
WHERE
    l_shipdate >= '1994-01-01'
    AND l_shipdate < date '1994-01-01' + interval '1 year'
    AND l_discount BETWEEN 0.06 - 0.01 AND 0.06 + 0.01
    AND l_quantity < 24;