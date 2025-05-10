# Common Prefix reference table

## Prefix format

```php-template
<Resource><Size><Timing><ModuleName>
```

## Code legend

| Code | Feild | Meaning | Notes |
| --------------- | --------------- | --------------- | --------------- |
| RG | Resource | Register-only | 100% flip-flops / LUTs, no block RAM |
| ME | Resource | Memory-heavy | Mainly BRAM/SRAM or other true storage |
| s | Size | Small | Tiny footprint, minimal LUT/FF usage |
| m | Size | Medium | Moderate logic, fits simple pipelines or mid-sized units |
| l | Size | Large | Complex FSMs, wide buses or big data paths |
| u | Timing | Unit-cycle (1 cycle) | Pure combinational path, result in a single clock tick |
| d | Timing | Dual-cycle (2 cycles) | Splits logic over two clocks |
| t | Timing | Tri-cycle (3 cycles) | Three-stage sequence or light pipeline |
| p | Timing | Pipeline (≥4 stages) | Deeply pipelined, four or more stages |
