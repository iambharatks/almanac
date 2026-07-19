class Solution {
public:
    long long maxSubarraySum(vector<int>& nums, int k) {
        vector<long long> minSums(k,0);
        long long res = LONG_LONG_MIN;
        long long sum = 0;
        for(int i = 0 ; i < size(nums) ; i++){
            sum += nums[i];
            if(i >= k-1)
                res = max(res,sum-minSums[i%k]);
            if(i < k-1){
                minSums[i%k] = sum;
            }else{
                minSums[i%k] = min(minSums[i%k],sum);
            }
        }
        return res;
    }
};