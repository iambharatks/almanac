class Solution {
public:
    long long gcdSum(vector<int>& nums) {
        int mx = nums[0];
        for(int i = 0 ; i < size(nums) ; i++){
            mx = max(nums[i],mx);
            nums[i] = __gcd(nums[i],mx);
        }
        sort(begin(nums),end(nums));
        long long res = 0;
        int n = size(nums);
        n /= 2;
        for(int i = 0 ; i < n ; i++){
            res += __gcd(nums[i],nums[size(nums)-i-1]);
        }
        return res;
    }
};