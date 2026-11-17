#!/system/bin/sh
# On the phone: one CSV row per second with thermal status, skin, the hottest CPU and NPU sensors and both CPU
# clusters' current clocks (kHz), and from dumpsys battery the battery current (mA, refreshed every 10-25 s; real
# only on battery power), voltage (mV) and charge counter (uAh, 4 mAh steps). Stopped with pkill -f thermal-sampler.
echo "t,status,skin,cpu_max,nsp_max,f_p0,f_p6,ma,mv,uah"
t0=$(date +%s)
while true; do
  d=$(dumpsys thermalservice)
  st=$(echo "$d" | sed -n 's/^Thermal Status: //p')
  cur=$(echo "$d" | sed -n '/Current temperatures from HAL/,/Current cooling/p')
  sk=$(echo "$cur" | sed -n 's/.*mValue=\([0-9.]*\), mType=3, mName=skin.*/\1/p' | head -1)
  cm=$(echo "$cur" | sed -n 's/.*mValue=\([0-9.]*\), mType=0, mName=CPU.*/\1/p' | sort -n | tail -1)
  nm=$(echo "$cur" | sed -n 's/.*mValue=\([0-9.]*\), mType=9, mName=nsp.*/\1/p' | sort -n | tail -1)
  f0=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq)
  f6=$(cat /sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq)
  b=$(dumpsys battery)
  ma=$(echo "$b" | sed -n 's/.*Battery current : //p' | head -1)
  mv=$(echo "$b" | sed -n 's/^ *voltage: //p' | head -1)
  uah=$(echo "$b" | sed -n 's/.*Charge counter: //p' | head -1)
  echo "$(( $(date +%s) - t0 )),$st,$sk,$cm,$nm,$f0,$f6,$ma,$mv,$uah"
  sleep 1
done
