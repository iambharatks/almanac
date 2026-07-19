class Solution {
public:
    long long maxSubarraySum(vector<int>& nums, int k) {
        vector<long long> kBucket(k,LONG_LONG_MAX/2);
        long long prefixSum = 0, maxSum = LONG_LONG_MIN;
        kBucket[k-1] = 0;
        for(int i = 0 ; i < size(nums) ; i++){
            prefixSum += nums[i];
            maxSum = max(maxSum,prefixSum-kBucket[i%k]);
            kBucket[i%k] = min(kBucket[i%k],prefixSum);
        }
        return maxSum;
    }
};