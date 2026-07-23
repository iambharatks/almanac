class Solution {
public:
    int uniqueXorTriplets(vector<int>& nums) {
        int n = size(nums);
        if(n <= 2) return n;
        if(n == 3) return n+1;
        int tot = 1;
        while(tot <= n){
            tot <<= 1;
        }
        return tot;
    }
};